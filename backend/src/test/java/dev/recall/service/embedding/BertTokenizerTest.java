package dev.recall.service.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** WordPiece rules on a tiny vocabulary; the real 30 522-entry vocabulary is checked in OnnxEmbeddingServiceIT. */
class BertTokenizerTest {

    private static final List<String> VOCAB = List.of(
            "[PAD]", "[UNK]", "[CLS]", "[SEP]", "un", "##aff", "##able", "git", "reset", "-", "~", "1", "head",
            "cafe", ",", "!", "hello", "world", "中", "文", "a", "##b");

    private final BertTokenizer tokenizer = BertTokenizer.fromVocab(VOCAB);

    @Test
    void splitsWordsIntoLongestKnownPieces() {
        assertThat(tokenizer.tokenize("unaffable")).containsExactly("un", "##aff", "##able");
    }

    @Test
    void lowerCasesSplitsPunctuationAndStripsAccents() {
        assertThat(tokenizer.tokenize("Hello, WORLD!")).containsExactly("hello", ",", "world", "!");
        assertThat(tokenizer.tokenize("Café")).containsExactly("cafe");
        assertThat(tokenizer.tokenize("git reset --soft HEAD~1"))
                .containsExactly("git", "reset", "-", "-", "[UNK]", "head", "~", "1");
    }

    @Test
    void unknownWordsBecomeUnkAndOverlongWordsToo() {
        assertThat(tokenizer.tokenize("zzz")).containsExactly("[UNK]");
        assertThat(tokenizer.tokenize("ab")).containsExactly("a", "##b");
        assertThat(tokenizer.tokenize("a".repeat(BertTokenizer.MAX_WORD_CHARS + 1))).containsExactly("[UNK]");
    }

    @Test
    void separatesCjkCharactersAndDropsControlCharacters() {
        assertThat(tokenizer.tokenize("中文")).containsExactly("中", "文");
        assertThat(tokenizer.basicTokenize("hello\u0000​world\tgit\nreset"))
                .containsExactly("helloworld", "git", "reset");
    }

    @Test
    void encodeAddsClsAndSepAndTruncates() {
        BertTokenizer.Encoding e = tokenizer.encode("hello world hello world", 4);

        assertThat(e.ids()).containsExactly(2, 16, 17, 3);
        assertThat(e.attentionMask()).containsExactly(1, 1, 1, 1);
        assertThat(e.tokenTypeIds()).containsExactly(0, 0, 0, 0);
    }

    @Test
    void emptyTextIsJustClsAndSep() {
        assertThat(tokenizer.encode("   ", 256).ids()).containsExactly(2, 3);
    }

    @Test
    void recognisesUnicodePunctuation() {
        assertThat(BertTokenizer.isPunctuation('«')).isTrue();
        assertThat(BertTokenizer.isPunctuation('$')).isTrue();
        assertThat(BertTokenizer.isPunctuation('a')).isFalse();
    }

    @Test
    void vocabularyMustHaveSpecialTokens() {
        assertThatThrownBy(() -> BertTokenizer.fromVocab(List.of("[PAD]", "hello")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[CLS]");
    }
}
