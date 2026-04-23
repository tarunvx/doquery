package io.github.tarunvx.core.port.out;

import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * Secondary (driven) port — manages N named {@link VectorStore} instances
 * (one per logical partition: tenant, user, project, topic…).
 *
 * <p>Implementations may back this with on-disk JSON files (SimpleVectorStore),
 * separate collections (Chroma), schemas/tables (PgVector), namespaces (Pinecone)…</p>
 *
 * <p>Core never knows the backing technology — it only asks for a store by id.</p>
 */
public interface VectorStoreManager {

    /** Default store id used when caller does not specify one. */
    String DEFAULT_STORE_ID = "default";

    /** Get (or lazily create + load) the store identified by {@code storeId}. */
    VectorStore get(String storeId);

    /** All known store ids (in-memory + persisted). */
    List<String> listStores();

    /** Flush the given store to durable storage (no-op for write-through backends). */
    void persist(String storeId);

    /** Normalize a caller-provided id to a safe, canonical form. */
    String normalize(String storeId);
}

