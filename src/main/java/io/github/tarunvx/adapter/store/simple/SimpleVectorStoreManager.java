package io.github.tarunvx.adapter.store.simple;

import io.github.tarunvx.core.port.out.VectorStoreManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * SimpleVectorStore-based {@link VectorStoreManager} — JSON file persistence on local disk.
 * One {@link SimpleVectorStore} per {@code storeId}, persisted as
 * {@code <baseDir>/<storeId>.json}.
 *
 * Activated by {@code app.vectorstore.provider=simple} (default).
 * Replace with another adapter (Chroma / PgVector / Redis…) by implementing
 * {@link VectorStoreManager} and switching the {@code provider} property.
 */
@Component
@ConditionalOnProperty(prefix = "app.vectorstore", name = "provider",
        havingValue = "simple", matchIfMissing = true)
public class SimpleVectorStoreManager implements VectorStoreManager {

    private static final Logger log = LoggerFactory.getLogger(SimpleVectorStoreManager.class);
    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9._-]");

    private final Path baseDir;
    private final EmbeddingModel embeddingModel;
    private final ConcurrentMap<String, SimpleVectorStore> stores = new ConcurrentHashMap<>();

    public SimpleVectorStoreManager(@Value("${app.vectorstore.dir}") String baseDir,
                                    EmbeddingModel embeddingModel) throws IOException {
        this.baseDir = Path.of(baseDir).toAbsolutePath();
        this.embeddingModel = embeddingModel;
        Files.createDirectories(this.baseDir);
        migrateLegacyFile();
    }

    @Override
    public VectorStore get(String storeId) {
        return stores.computeIfAbsent(normalize(storeId), this::loadOrCreate);
    }

    @Override
    public List<String> listStores() {
        Set<String> ids = new TreeSet<>(stores.keySet());
        File[] files = baseDir.toFile().listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) ids.add(f.getName().replaceFirst("\\.json$", ""));
        }
        if (ids.isEmpty()) ids.add(DEFAULT_STORE_ID);
        return new ArrayList<>(ids);
    }

    @Override
    public void persist(String storeId) {
        String id = normalize(storeId);
        SimpleVectorStore s = stores.get(id);
        if (s != null) s.save(fileFor(id));
    }

    @Override
    public String normalize(String storeId) {
        if (storeId == null) return DEFAULT_STORE_ID;
        String trimmed = storeId.trim();
        if (trimmed.isEmpty()) return DEFAULT_STORE_ID;
        String cleaned = SAFE.matcher(trimmed).replaceAll("_");
        return cleaned.isBlank() ? DEFAULT_STORE_ID : cleaned;
    }

    @PreDestroy
    public void persistAll() {
        stores.forEach((id, s) -> {
            try { s.save(fileFor(id)); }
            catch (Exception e) { log.warn("Failed to persist store {}: {}", id, e.getMessage()); }
        });
    }

    private SimpleVectorStore loadOrCreate(String id) {
        SimpleVectorStore s = SimpleVectorStore.builder(embeddingModel).build();
        File f = fileFor(id);
        if (f.exists()) {
            log.info("Loading vector store '{}' from {}", id, f);
            s.load(f);
        } else {
            log.info("Creating new vector store '{}' at {}", id, f);
        }
        return s;
    }

    private File fileFor(String id) {
        return baseDir.resolve(id + ".json").toFile();
    }

    /** One-time migration of pre-existing single-file store to default partition. */
    private void migrateLegacyFile() throws IOException {
        Path legacy = Path.of("./data/vectorstore.json").toAbsolutePath();
        Path target = baseDir.resolve(DEFAULT_STORE_ID + ".json");
        if (Files.exists(legacy) && !Files.exists(target)) {
            Files.move(legacy, target);
            log.info("Migrated legacy vector store {} → {}", legacy, target);
        }
    }
}
