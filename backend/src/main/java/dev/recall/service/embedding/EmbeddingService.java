package dev.recall.service.embedding;

/**
 * Turns text into a 384-dimensional unit vector. Which implementation runs is decided by configuration
 * ({@code recall.embedding.provider}); nothing else in the code knows or cares.
 */
public interface EmbeddingService {

    int DIMENSIONS = 384;

    float[] embed(String text);

    /** Short identifier for logs and health output. */
    String name();
}
