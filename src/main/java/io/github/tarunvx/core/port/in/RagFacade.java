package io.github.tarunvx.core.port.in;

import io.github.tarunvx.core.model.Answer;

import java.io.IOException;
import java.io.InputStream;

/**
 * Primary (driving) port — entry point used by every UI adapter
 * (Vaadin, REST, CLI…). UIs MUST NOT depend on anything else from core.
 */
public interface RagFacade {

    /**
     * @return number of chunks added to the knowledge base
     */
    int ingest(String filename, InputStream content) throws IOException;

    Answer ask(String question);
}

