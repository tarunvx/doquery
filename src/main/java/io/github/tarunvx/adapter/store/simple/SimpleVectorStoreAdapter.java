package io.github.tarunvx.adapter.store.simple;

import io.github.tarunvx.core.port.out.VectorStorePersister;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SimpleVectorStore adapter — JSON file persistence on local disk.
 * Activated by {@code app.vectorstore.provider=simple} (default).
 * Replace by another adapter (Chroma, PgVector, Redis…) bound to a different value.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.vectorstore", name = "provider", havingValue = "simple", matchIfMissing = true)
public class SimpleVectorStoreAdapter {

    private final Path storePath;
    private SimpleVectorStore store;

    public SimpleVectorStoreAdapter(@Value("${app.vectorstore.path}") String storePath) {
        this.storePath = Path.of(storePath);
    }

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) throws IOException {
        Files.createDirectories(storePath.toAbsolutePath().getParent());
        SimpleVectorStore s = SimpleVectorStore.builder(embeddingModel).build();
        if (Files.exists(storePath)) s.load(storePath.toFile());
        this.store = s;
        return s;
    }

    @Bean
    public VectorStorePersister vectorStorePersister() {
        return () -> { if (store != null) store.save(storePath.toFile()); };
    }

    @PreDestroy
    public void onShutdown() {
        if (store != null) store.save(storePath.toFile());
    }
}

