package dev.recall.service.errors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns messy error output into a stable key, so the same problem matches even when ports, paths, hashes or times
 * differ. Steps (spec §5.2): strip escape codes; keep the error-ish lines plus the last three, at most 20; lower-case
 * and replace volatile parts with placeholders; collapse whitespace; SHA-256 of the result is the fingerprint.
 * Replacement order is time, url, path, hash, str, num, user (see docs/DECISIONS.md for why it differs from the spec).
 */
@Component
public class ErrorFingerprinter {

    public record Fingerprint(String normalized, String sha256) {}

    static final int MAX_LINES = 20;
    static final int TAIL_LINES = 3;

    private static final Pattern ANSI =
            Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]|\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|\\u001B[@-Z\\\\-_]");
    private static final Pattern ERRORISH = Pattern.compile(
            "error|fatal|exception|denied|refused|not found|cannot|failed|panic|traceback", Pattern.CASE_INSENSITIVE);

    private record Replacement(Pattern pattern, String placeholder) {}

    private static final List<Replacement> REPLACEMENTS = List.of(
            // 2026-09-24T10:15:02.123Z, 2026-09-24 10:15:02, 10:15:02.5
            new Replacement(Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}(?:[t ]\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?"
                    + "(?:z|[+-]\\d{2}:?\\d{2})?)?\\b|\\b\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b"), "<time>"),
            new Replacement(Pattern.compile("\\b[a-z][a-z0-9+.-]*://[^\\s\"'<>)]+"), "<url>"),
            // C:\Users\x\app, C:/x, /home/x/app/file.js, ~/x
            new Replacement(Pattern.compile("\\b[a-z]:[\\\\/][^\\s\"'<>|:*?]*"
                    + "|(?<![\\w.:/~-])~?/[\\w.@+-]+(?:/[\\w.@+-]*)*"), "<path>"),
            // 0x7ffd..., UUIDs, git shas and other hex ids (need a letter and a digit so plain numbers stay numbers)
            new Replacement(Pattern.compile("\\b0x[0-9a-f]+\\b|\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{12}\\b|\\b(?=[0-9a-f]*[a-f])(?=[0-9a-f]*\\d)[0-9a-f]{7,64}\\b"), "<hash>"),
            // "quoted", `quoted`, 'quoted' (a ' right after a letter is an apostrophe: can't, doesn't)
            new Replacement(Pattern.compile("\"[^\"\\n]*\"|`[^`\\n]*`|(?<![a-z0-9])'[^'\\n]*'"), "<str>"),
            new Replacement(Pattern.compile("\\b\\d+(?:\\.\\d+)*\\b"), "<num>"));

    public Fingerprint fingerprint(String rawError, String osUser) {
        String normalized = normalize(rawError, osUser);
        return new Fingerprint(normalized, sha256(normalized));
    }

    public String normalize(String rawError, String osUser) {
        String text = ANSI.matcher(rawError == null ? "" : rawError).replaceAll("").replace("\r", "");
        String joined = String.join("\n", relevantLines(text)).toLowerCase(Locale.ROOT);
        for (Replacement r : REPLACEMENTS) {
            joined = r.pattern().matcher(joined).replaceAll(r.placeholder());
        }
        if (osUser != null && osUser.strip().length() >= 3) {
            joined = Pattern.compile("\\b" + Pattern.quote(osUser.strip().toLowerCase(Locale.ROOT)) + "\\b")
                    .matcher(joined).replaceAll("<user>");
        }
        return joined.replaceAll("\\s+", " ").strip();
    }

    /** Error-ish lines plus the last three non-blank lines, in their original order; the last 20 if there are more. */
    static List<String> relevantLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        Set<Integer> keep = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (ERRORISH.matcher(lines.get(i)).find()) {
                keep.add(i);
            }
        }
        for (int i = Math.max(0, lines.size() - TAIL_LINES); i < lines.size(); i++) {
            keep.add(i);
        }
        List<Integer> ordered = keep.stream().sorted().toList();
        return ordered.subList(Math.max(0, ordered.size() - MAX_LINES), ordered.size()).stream().map(lines::get).toList();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
