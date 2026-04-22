package io.github.tarunvx.core.port.out;

import org.springframework.ai.document.Document;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Secondary (driven) port — turns a binary document stream into Spring AI
 * {@link Document}s with at minimum {@code source} and {@code page_number} metadata.
 * Implementations: PDF (PDFBox), DOCX (Tika), HTML, etc.
 */
public interface DocumentParser {

    /** @return true if this parser can handle the given filename / mime hint */
    boolean supports(String filename);

    List<Document> parse(String filename, InputStream content) throws IOException;
}

