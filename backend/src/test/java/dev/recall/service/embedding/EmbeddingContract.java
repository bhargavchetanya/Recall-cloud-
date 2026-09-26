package dev.recall.service.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Behaviour every embedder must have, run against both implementations (hashing as a unit test, ONNX as an IT).
 * The paraphrases share some words on purpose, so the weaker hashing embedder can pass as well.
 */
abstract class EmbeddingContract {

    record Triple(String anchor, String paraphrase, String unrelated) {}

    abstract EmbeddingService embedder();

    static List<Triple> triples() {
        return List.of(
                new Triple("undo the last commit", "undo my last git commit", "bake banana bread at home"),
                new Triple("list running docker containers", "show the running containers in docker",
                        "water the garden plants"),
                new Triple("kill the process on port 3000", "free port 3000 by killing its process",
                        "translate this poem into french"),
                new Triple("install python dependencies", "install the dependencies for python",
                        "book a flight to paris"));
    }

    @Test
    void identicalTextGivesIdenticalVector() {
        String text = "git reset --soft HEAD~1";
        assertThat(embedder().embed(text)).containsExactly(embedder().embed(text));
    }

    @Test
    void vectorsHave384DimensionsAndUnitLength() {
        float[] v = embedder().embed("docker compose up -d");
        assertThat(v).hasSize(EmbeddingService.DIMENSIONS);
        assertThat(Vectors.cosine(v, v)).isCloseTo(1.0, within(1e-5));
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-4));
    }

    @ParameterizedTest
    @MethodSource("triples")
    void paraphraseIsCloserThanUnrelatedText(Triple t) {
        float[] anchor = embedder().embed(t.anchor());
        double paraphrase = Vectors.cosine(anchor, embedder().embed(t.paraphrase()));
        double unrelated = Vectors.cosine(anchor, embedder().embed(t.unrelated()));
        assertThat(paraphrase).as("'%s' vs '%s'", t.anchor(), t.paraphrase()).isGreaterThan(unrelated);
    }

    @Test
    void differentTextsGiveDifferentVectors() {
        assertThat(embedder().embed("npm test")).isNotEqualTo(embedder().embed("npm install"));
    }
}
