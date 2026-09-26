package dev.recall.service.embedding;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Deterministic, model-free embeddings for tests and offline use: lower-case the text, take every word and every
 * character 3-gram of each word (with boundary marks), hash each feature into one of 384 buckets with a hash-derived
 * sign, then L2-normalize. Texts that share words and word pieces end up close; it knows nothing about synonyms.
 */
public final class HashingEmbeddingService implements EmbeddingService {

    private static final float WORD_WEIGHT = 2.0f;
    private static final float TRIGRAM_WEIGHT = 1.0f;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            add(v, "w:" + word, WORD_WEIGHT);
            String padded = "#" + word + "#";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                add(v, "g:" + padded.substring(i, i + 3), TRIGRAM_WEIGHT);
            }
        }
        return Vectors.normalize(v);
    }

    @Override
    public String name() {
        return "hashing";
    }

    private static void add(float[] v, String feature, float weight) {
        long h = fnv1a(feature);
        int bucket = (int) Long.remainderUnsigned(h, DIMENSIONS);
        v[bucket] += (h < 0 ? -1 : 1) * weight;
    }

    static long fnv1a(String s) {
        long h = FNV_OFFSET;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= FNV_PRIME;
        }
        return h;
    }
}
