package io.github.tarunvx.core.service;

import io.github.tarunvx.core.model.Answer;
import io.github.tarunvx.core.model.Citation;
import io.github.tarunvx.core.port.in.RagFacade;
import io.github.tarunvx.core.port.out.DocumentParser;
import io.github.tarunvx.core.port.out.VectorStorePersister;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure orchestration. Talks to:
 *  - {@link DocumentParser} (out-port, swapped by parser adapter),
 *  - {@link TextSplitter}   (Spring AI abstraction),
 *  - {@link VectorStore}    (Spring AI abstraction → swapped by store adapter),
 *  - {@link ChatClient}     (Spring AI abstraction → wired with provider via LLM adapter),
 *  - {@link VectorStorePersister} (out-port, optional).
 *
 * no references to any concrete UI / DB / LLM provider.
 */
@Service
public class RagService implements RagFacade {

    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9._-]");

    private final List<DocumentParser> parsers;
    private final TextSplitter splitter;
    private final VectorStore vectorStore;
    private final VectorStorePersister persister;
    private final ChatClient chatClient;
    private final long maxBytes;
    private final int topK;
    private final double threshold;

    public RagService(List<DocumentParser> parsers,
                      TextSplitter splitter,
                      VectorStore vectorStore,
                      VectorStorePersister persister,
                      ChatClient chatClient,
                      @Value("${app.ingest.max-bytes}") long maxBytes,
                      @Value("${app.rag.top-k}") int topK,
                      @Value("${app.rag.similarity-threshold}") double threshold) {
        this.parsers = parsers;
        this.splitter = splitter;
        this.vectorStore = vectorStore;
        this.persister = persister;
        this.chatClient = chatClient;
        this.maxBytes = maxBytes;
        this.topK = topK;
        this.threshold = threshold;
    }

    @Override
    public int ingest(String filename, InputStream content) throws IOException {
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
        vectorStore.add(chunks);
        persister.persist();
        return chunks.size();
    }

    @Override
    public Answer ask(String question) {
        if (question == null || question.isBlank()) {
            return new Answer("Please enter a question.", List.of());
        }
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(topK)
                        .similarityThreshold(threshold)
                        .build());

        if (hits == null || hits.isEmpty()) {
            return new Answer("I cannot find the answer in the provided documents.", List.of());
        }

        String context = hits.stream()
                .map(d -> "[" + label(d) + "]\n" + d.getText())
                .collect(Collectors.joining("\n---\n"));

        String userPrompt = """
                CONTEXT:
                %s

                QUESTION: %s
                """.formatted(context, question);

        String text = chatClient.prompt().user(userPrompt).call().content();
        return new Answer(text, dedupe(hits));
    }

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

