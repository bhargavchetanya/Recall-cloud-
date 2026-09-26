package dev.recall.service.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class VectorsTest {

    @Test
    void normalizeScalesToUnitLengthAndKeepsZero() {
        assertThat(Vectors.normalize(new float[] {3f, 4f})).containsExactly(0.6f, 0.8f);
        assertThat(Vectors.normalize(new float[] {0f, 0f})).containsExactly(0f, 0f);
    }

    @Test
    void cosineOfKnownVectors() {
        assertThat(Vectors.cosine(new float[] {1, 0}, new float[] {0, 1})).isCloseTo(0, within(1e-9));
        assertThat(Vectors.cosine(new float[] {1, 1}, new float[] {2, 2})).isCloseTo(1, within(1e-9));
        assertThat(Vectors.cosine(new float[] {1, 0}, new float[] {-1, 0})).isCloseTo(-1, within(1e-9));
        assertThat(Vectors.cosine(new float[] {0, 0}, new float[] {1, 0})).isZero();
    }

    @Test
    void pgLiteral() {
        assertThat(Vectors.toPgLiteral(new float[] {0.5f, -1f, 0f})).isEqualTo("[0.5,-1.0,0.0]");
        assertThat(Vectors.toPgLiteral(new float[0])).isEqualTo("[]");
    }
}
