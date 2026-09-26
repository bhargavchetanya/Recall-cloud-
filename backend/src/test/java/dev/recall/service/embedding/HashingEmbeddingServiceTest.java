package dev.recall.service.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashingEmbeddingServiceTest extends EmbeddingContract {

    private final HashingEmbeddingService hashing = new HashingEmbeddingService();

    @Override
    EmbeddingService embedder() {
        return hashing;
    }

    @Test
    void isDeterministicAcrossInstances() {
        assertThat(new HashingEmbeddingService().embed("kubectl get pods"))
                .containsExactly(new HashingEmbeddingService().embed("kubectl get pods"));
    }

    @Test
    void ignoresCaseAndPunctuation() {
        assertThat(hashing.embed("Git STATUS!")).containsExactly(hashing.embed("git status"));
    }

    @Test
    void textWithoutWordsIsTheZeroVector() {
        assertThat(hashing.embed("--- !!! ...")).containsOnly(0f);
    }

    @Test
    void fnvMatchesTheReferenceValue() {
        // FNV-1a 64-bit of "a" from the published test vectors.
        assertThat(HashingEmbeddingService.fnv1a("a")).isEqualTo(0xaf63dc4c8601ec8cL);
    }

    @Test
    void nameIsHashing() {
        assertThat(hashing.name()).isEqualTo("hashing");
    }
}
