package io.github.tarunvx.core.port.in;

import io.github.tarunvx.core.model.Answer;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * Primary (driving) port — entry point used by every UI adapter
 * (Vaadin, REST, CLI…). UIs MUST NOT depend on anything else from core.
 */
public interface RagFacade {

    /** Knowledge sources the assistant is allowed to consult. */
    enum KnowledgeMode {
        /** ONLY the documents in the selected vector store; refuse otherwise. */
        STRICT,
        /** Documents first; if insufficient, the LLM's general knowledge — clearly disclosed. */
        HYBRID
    }

    /**
     * Ingest a document into the named vector store.
     * @param storeId target store id; {@code null}/blank → default store
     * @return number of chunks added
     */
    int ingest(String storeId, String filename, InputStream content) throws IOException;

    /** Non-streaming Q&amp;A. */
    Answer ask(String storeId, String question, KnowledgeMode mode);

    /**
     * Streaming Q&amp;A. {@code tokenSink} is invoked with each incremental token as
     * it arrives from the LLM. The returned {@link Answer} contains the full
     * concatenated text plus citations (after stream completion).
     */
    Answer askStreaming(String storeId, String question, KnowledgeMode mode, Consumer<String> tokenSink);

    /** Available vector store ids. */
    List<String> listStores();

    /** Whether streaming is enabled by configuration (UI hint). */
    boolean isStreamingEnabled();

    /** Default mode from configuration (UI hint). */
    KnowledgeMode defaultMode();
}
