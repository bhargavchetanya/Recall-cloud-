package dev.recall.service.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The embedding contract against the real model, plus checks that tokenization matches BERT-uncased and that the
 * glossary actually moves a command toward the way people ask for it. Needs backend/models (scripts/fetch-model.sh).
 */
class OnnxEmbeddingServiceIT extends EmbeddingContract {

    static final Path MODELS = Path.of("models");
    static OnnxEmbeddingService onnx;

    @BeforeAll
    static void load() {
        onnx = OnnxEmbeddingService.load(MODELS);
    }

    @AfterAll
    static void close() throws Exception {
        onnx.close();
    }

    @Override
    EmbeddingService embedder() {
        return onnx;
    }

    @Test
    void tokenizesLikeBertUncased() throws Exception {
        BertTokenizer tokenizer = BertTokenizer.fromVocab(
                Files.readAllLines(MODELS.resolve(OnnxEmbeddingService.VOCAB_FILE), StandardCharsets.UTF_8));

        assertThat(tokenizer.tokenize("unaffable")).containsExactly("una", "##ffa", "##ble");
        assertThat(tokenizer.encode("Hello, world!", 256).ids()).containsExactly(101, 7592, 1010, 2088, 999, 102);
    }

    @Test
    void longInputIsTruncatedNotRejected() {
        assertThat(onnx.embed("npm ".repeat(2000))).hasSize(EmbeddingService.DIMENSIONS);
    }

    @Test
    void glossaryPullsTheRightCommandTowardTheQuestion() {
        CommandGlossary glossary = new CommandGlossary();
        float[] question = onnx.embed("undo my last commit but keep the changes");

        double soft = Vectors.cosine(question, onnx.embed(glossary.intentText("git reset --soft HEAD~1")));
        double softRaw = Vectors.cosine(question, onnx.embed("git reset --soft HEAD~1"));
        double prune = Vectors.cosine(question, onnx.embed(glossary.intentText("docker system prune -af")));
        double hard = Vectors.cosine(question, onnx.embed(glossary.intentText("git reset --hard origin/main")));

        assertThat(soft).as("with glossary vs raw command").isGreaterThan(softRaw);
        assertThat(soft).isGreaterThan(prune).isGreaterThan(hard);
    }

    @Test
    void meanPoolingIgnoresMaskedTokens() {
        float[][] tokens = {{1f, 2f}, {3f, 4f}, {100f, 100f}};
        float[] pooled = OnnxEmbeddingService.meanPool(padTo384(tokens), new long[] {1, 1, 0});
        assertThat(pooled[0]).isEqualTo(2f);
        assertThat(pooled[1]).isEqualTo(3f);
    }

    @Test
    void missingModelGivesAnActionableError(@org.junit.jupiter.api.io.TempDir Path empty) {
        assertThatThrownBy(() -> OnnxEmbeddingService.load(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scripts/fetch-model.sh")
                .hasMessageContaining("offline");
    }

    @Test
    void nameIsTheModel() {
        assertThat(onnx.name()).isEqualTo("all-MiniLM-L6-v2");
    }

    private static float[][] padTo384(float[][] tokens) {
        float[][] out = new float[tokens.length][EmbeddingService.DIMENSIONS];
        for (int i = 0; i < tokens.length; i++) {
            System.arraycopy(tokens[i], 0, out[i], 0, tokens[i].length);
        }
        return out;
    }
}
