package io.github.tarunvx.core.port.out;

/**
 * Secondary port — flush the vector store to durable storage.
 * Some backends (PgVector, Chroma) write through and provide a no-op;
 * file-based stores (SimpleVectorStore) actually serialize.
 */
@FunctionalInterface
public interface VectorStorePersister {
    void persist();
}

