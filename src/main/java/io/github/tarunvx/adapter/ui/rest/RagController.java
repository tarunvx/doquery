package io.github.tarunvx.adapter.ui.rest;

import io.github.tarunvx.core.model.Answer;
import io.github.tarunvx.core.port.in.RagFacade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Headless UI adapter — exposes the same {@link RagFacade} over HTTP/JSON.
 * Demonstrates that the core is UI-agnostic: Vaadin and REST coexist with no changes to core.
 */
@RestController
@RequestMapping("/api")
public class RagController {

    private final RagFacade rag;

    public RagController(RagFacade rag) {
        this.rag = rag;
    }

    @GetMapping("/stores")
    public List<String> stores() {
        return rag.listStores();
    }

    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "storeId", required = false) String storeId) throws IOException {
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "empty file"));
        int chunks = rag.ingest(storeId, file.getOriginalFilename(), file.getInputStream());
        return ResponseEntity.ok(Map.of(
                "filename", file.getOriginalFilename(),
                "chunks", chunks,
                "storeId", storeId == null ? "default" : storeId));
    }

    public record AskRequest(String storeId, String question, RagFacade.KnowledgeMode mode) {}

    @PostMapping("/ask")
    public Answer ask(@RequestBody AskRequest req) {
        return rag.ask(req.storeId(), req.question(),
                req.mode() == null ? rag.defaultMode() : req.mode());
    }
}
