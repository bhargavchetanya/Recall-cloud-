package dev.recall.service.embedding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU cache in front of an embedder. Terminal history repeats a lot (git status, npm test), so most embeddings are
 * computed once. Returned arrays are copies, so callers cannot corrupt the cache.
 */
public final class CachingEmbeddingService implements EmbeddingService, AutoCloseable {

    private final EmbeddingService delegate;
    private final Map<String, float[]> cache;

    public CachingEmbeddingService(EmbeddingService delegate, int maxEntries) {
        this.delegate = delegate;
        this.cache = new LinkedHashMap<>(Math.max(16, maxEntries / 4), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > maxEntries;
            }
        };
    }

    @Override
    public float[] embed(String text) {
        float[] cached;
        synchronized (cache) {
            cached = cache.get(text);
        }
        if (cached == null) {
            cached = delegate.embed(text);
            synchronized (cache) {
                cache.put(text, cached);
            }
        }
        return cached.clone();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    /** Releases the delegate's native resources (the ONNX session) when the Spring context closes. */
    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
