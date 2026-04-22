package io.github.tarunvx.adapter.parser.pdf;

import io.github.tarunvx.core.port.out.DocumentParser;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class PdfDocumentParser implements DocumentParser {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    @Override
    public List<Document> parse(String filename, InputStream content) throws IOException {
        byte[] bytes = readAll(content);
        validateMagic(bytes);
        PagePdfDocumentReader reader = new PagePdfDocumentReader(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        return reader.get();
    }

    private byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    private void validateMagic(byte[] bytes) {
        if (bytes.length < 4) throw new IllegalArgumentException("Empty PDF");
        for (int i = 0; i < 4; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                throw new IllegalArgumentException("Not a valid PDF (bad magic bytes)");
            }
        }
    }
}

