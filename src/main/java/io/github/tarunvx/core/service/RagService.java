package io.github.tarunvx.core.service;

import io.github.tarunvx.core.model.Answer;
import io.github.tarunvx.core.model.Citation;
import io.github.tarunvx.core.port.in.RagFacade;
import io.github.tarunvx.core.port.out.DocumentParser;
import io.github.tarunvx.core.port.out.VectorStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure orchestration. Talks only to Spring AI abstractions and out-ports.
 * No references to any concrete UI / DB / LLM provider.
 */
@Service
public class RagService implements RagFacade {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9._-]");

    private static final String SYSTEM_STRICT = """
            You are Doquery, a precise assistant. Answer the user's question using ONLY the
            provided CONTEXT. If the context does not contain the answer, reply exactly:
            "I cannot find the answer in the provided documents."
            Be concise. Do not invent facts. Do not invent citations.
            """;

    private static final String SYSTEM_HYBRID = """
            You are Doquery, a precise assistant. Prefer the provided CONTEXT.
            If the CONTEXT fully answers the question, answer from it and add no extra facts.
            If the CONTEXT is insufficient or empty, you MAY use your general knowledge,
            but you MUST:
              1. Prefix that part with the marker [general knowledge].
              2. Be conservative — never fabricate names, numbers, dates, quotes, code, APIs or URLs.
              3. If you are not confident, say "I am not sure." instead of guessing.
            Be concise.
            """;

    private final List<DocumentParser> parsers;
    private final TextSplitter splitter;
    private final VectorStoreManager stores;
    private final ChatClient chatClient;
    private final long maxBytes;
    private final int topK;
    private final double threshold;
    private final boolean streamingEnabled;
    private final KnowledgeMode defaultMode;

    public RagService(List<DocumentParser> parsers,
                      TextSplitter splitter,
                      VectorStoreManager stores,
                      ChatClient chatClient,
                      @Value("${app.ingest.max-bytes}") long maxBytes,
                      @Value("${app.rag.top-k}") int topK,
                      @Value("${app.rag.similarity-threshold}") double threshold,
                      @Value("${app.chat.streaming:true}") boolean streamingEnabled,
                      @Value("${app.chat.mode:STRICT}") KnowledgeMode defaultMode) {
        this.parsers = parsers;
        this.splitter = splitter;
        this.stores = stores;
        this.chatClient = chatClient;
        this.maxBytes = maxBytes;
        this.topK = topK;
        this.threshold = threshold;
        this.streamingEnabled = streamingEnabled;
        this.defaultMode = defaultMode;
    }

    @Override
    public int ingest(String storeId, String filename, InputStream content) throws IOException {
        String safe = sanitize(filename);
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(safe))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No parser supports: " + safe));

        BoundedInputStream bounded = new BoundedInputStream(content, maxBytes);
        List<Document> pages = parser.parse(safe, bounded);
        for (Document d : pages) {
            d.getMetadata().putIfAbsent("source", safe);
        }
        List<Document> chunks = splitter.apply(pages);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No content extracted from " + safe);
        }
        String id = stores.normalize(storeId);
        VectorStore vs = stores.get(id);
        vs.add(chunks);
        stores.persist(id);
        return chunks.size();
    }


    @Override
    public Answer ask(String storeId, String question, KnowledgeMode mode) {
        Prepared p = prepare(storeId, question, mode);
        if (p.shortCircuit != null) return p.shortCircuit;
        String text = chatClient.prompt()
                .system(p.system)
                .user(p.userPrompt)
                .call()
                .content();
        return new Answer(text, dedupe(p.hits));
    }

    @Override
    public Answer askStreaming(String storeId, String question, KnowledgeMode mode, Consumer<String> tokenSink) {
        Prepared p = prepare(storeId, question, mode);
        if (p.shortCircuit != null) {
            if (tokenSink != null) tokenSink.accept(p.shortCircuit.text());
            return p.shortCircuit;
        }
        StringBuilder full = new StringBuilder();
        chatClient.prompt()
                .system(p.system)
                .user(p.userPrompt)
                .stream().content()
                .doOnNext(token -> {
                    full.append(token);
                    if (tokenSink != null) tokenSink.accept(token);
                })
                .blockLast();
        return new Answer(full.toString(), dedupe(p.hits));
    }

    @Override public boolean isStreamingEnabled() { return streamingEnabled; }
    @Override public KnowledgeMode defaultMode() { return defaultMode; }

    @Override
    public List<String> listStores() {
        return stores.listStores();
    }

    /** Shared retrieval + prompt assembly used by both ask paths. */
    private Prepared prepare(String storeId, String question, KnowledgeMode mode) {
        if (question == null || question.isBlank()) {
            return new Prepared(new Answer("Please enter a question.", List.of()), null, null, null);
        }
        KnowledgeMode m = (mode == null) ? defaultMode : mode;

        VectorStore vs = stores.get(stores.normalize(storeId));
        List<Document> hits = vs.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(topK)
                        .similarityThreshold(threshold)
                        .build());

        boolean haveContext = hits != null && !hits.isEmpty();

        if (!haveContext && m == KnowledgeMode.STRICT) {
            return new Prepared(
                    new Answer("I cannot find the answer in the provided documents.", List.of()),
                    null, null, null);
        }

        String context = haveContext
                ? hits.stream().map(d -> "[" + label(d) + "]\n" + d.getText())
                        .collect(Collectors.joining("\n---\n"))
                : "(no relevant context found in the selected store)";

        String userPrompt = """
                CONTEXT:
                %s

                QUESTION: %s
                """.formatted(context, question);

        String system = (m == KnowledgeMode.HYBRID) ? SYSTEM_HYBRID : SYSTEM_STRICT;
        return new Prepared(null, haveContext ? hits : List.of(), userPrompt, system);
    }

    private record Prepared(Answer shortCircuit, List<Document> hits, String userPrompt, String system) {}

    private String sanitize(String name) {
        String base = java.nio.file.Path.of(Objects.requireNonNullElse(name, "upload"))
                .getFileName().toString();
        String cleaned = SAFE_NAME.matcher(base).replaceAll("_");
        return cleaned.isBlank() ? "upload" : cleaned;
    }

    private String label(Document d) {
        Object src = d.getMetadata().getOrDefault("source", "unknown");
        Object page = d.getMetadata().get("page_number");
        return page != null ? src + " p." + page : String.valueOf(src);
    }

    private List<Citation> dedupe(List<Document> docs) {
        Set<String> seen = new LinkedHashSet<>();
        List<Citation> out = new ArrayList<>();
        for (Document d : docs) {
            String src = String.valueOf(d.getMetadata().getOrDefault("source", "unknown"));
            Integer page = toInt(d.getMetadata().get("page_number"));
            if (seen.add(src + "#" + page)) out.add(new Citation(src, page));
        }
        return out;
    }

    private Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Hard cap to avoid OOM regardless of UI / multipart settings. */
    private static final class BoundedInputStream extends java.io.FilterInputStream {
        private final long max;
        private long count;
        BoundedInputStream(InputStream in, long max) { super(in); this.max = max; }
        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++count > max) throw new IOException("Upload exceeds " + max + " bytes");
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) { count += n; if (count > max) throw new IOException("Upload exceeds " + max + " bytes"); }
            return n;
        }
    }
}
