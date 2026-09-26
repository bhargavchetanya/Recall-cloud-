package dev.recall.service.embedding;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The uncased BERT tokenizer all-MiniLM-L6-v2 was trained with: clean the text, split on whitespace and punctuation,
 * lower-case and strip accents, then split each word into the longest WordPiece units found in the vocabulary.
 * Written out here because the usual Java tokenizers download native code at runtime.
 */
public final class BertTokenizer {

    public record Encoding(long[] ids, long[] attentionMask, long[] tokenTypeIds) {}

    static final int MAX_WORD_CHARS = 100;

    private final Map<String, Integer> vocab;
    private final int cls;
    private final int sep;
    private final int unk;

    private BertTokenizer(Map<String, Integer> vocab) {
        this.vocab = vocab;
        this.cls = require("[CLS]");
        this.sep = require("[SEP]");
        this.unk = require("[UNK]");
    }

    /** One token per line; the line number is the id. */
    public static BertTokenizer fromVocab(List<String> lines) {
        Map<String, Integer> vocab = new HashMap<>(lines.size() * 2);
        for (int i = 0; i < lines.size(); i++) {
            vocab.putIfAbsent(lines.get(i).strip(), i);
        }
        return new BertTokenizer(vocab);
    }

    /** [CLS] tokens [SEP], truncated so the whole sequence has at most {@code maxTokens} entries. */
    public Encoding encode(String text, int maxTokens) {
        List<String> tokens = tokenize(text);
        int n = Math.min(tokens.size(), maxTokens - 2);
        long[] ids = new long[n + 2];
        ids[0] = cls;
        for (int i = 0; i < n; i++) {
            ids[i + 1] = vocab.getOrDefault(tokens.get(i), unk);
        }
        ids[n + 1] = sep;
        long[] mask = new long[ids.length];
        java.util.Arrays.fill(mask, 1L);
        return new Encoding(ids, mask, new long[ids.length]);
    }

    public List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String word : basicTokenize(text)) {
            wordPiece(word, out);
        }
        return out;
    }

    List<String> basicTokenize(String text) {
        StringBuilder spaced = new StringBuilder(text.length() + 16);
        text.codePoints().forEach(cp -> {
            if (cp == 0 || cp == 0xFFFD || (isControl(cp) && !Character.isWhitespace(cp))) {
                return;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                spaced.append(' ');
            } else if (isCjk(cp)) {
                spaced.append(' ').appendCodePoint(cp).append(' ');
            } else {
                spaced.appendCodePoint(cp);
            }
        });

        List<String> words = new ArrayList<>();
        for (String raw : spaced.toString().trim().split(" +")) {
            if (raw.isEmpty()) {
                continue;
            }
            String word = stripAccents(raw.toLowerCase(Locale.ROOT));
            StringBuilder current = new StringBuilder();
            word.codePoints().forEach(cp -> {
                if (isPunctuation(cp)) {
                    if (!current.isEmpty()) {
                        words.add(current.toString());
                        current.setLength(0);
                    }
                    words.add(new String(Character.toChars(cp)));
                } else {
                    current.appendCodePoint(cp);
                }
            });
            if (!current.isEmpty()) {
                words.add(current.toString());
            }
        }
        return words;
    }

    private void wordPiece(String word, List<String> out) {
        if (word.codePointCount(0, word.length()) > MAX_WORD_CHARS) {
            out.add("[UNK]");
            return;
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            String match = null;
            while (start < end) {
                String candidate = (start > 0 ? "##" : "") + word.substring(start, end);
                if (vocab.containsKey(candidate)) {
                    match = candidate;
                    break;
                }
                end = word.offsetByCodePoints(end, -1);
            }
            if (match == null) {
                out.add("[UNK]");
                return;
            }
            pieces.add(match);
            start = end;
        }
        out.addAll(pieces);
    }

    private int require(String token) {
        Integer id = vocab.get(token);
        if (id == null) {
            throw new IllegalArgumentException("Vocabulary lacks " + token);
        }
        return id;
    }

    private static String stripAccents(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        decomposed.codePoints()
                .filter(cp -> Character.getType(cp) != Character.NON_SPACING_MARK)
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static boolean isControl(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT;
    }

    static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F) || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
