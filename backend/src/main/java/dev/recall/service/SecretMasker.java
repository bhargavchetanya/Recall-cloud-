package dev.recall.service;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Replaces secret values with {@code ***}. The extension runs the same rules before sending; this second pass
 * protects against old or modified clients. Both are tested against {@code shared/fixtures/masking-cases.json}.
 * Rule order matters and must stay identical to {@code extension/src/masking.ts}.
 */
@Component
public class SecretMasker {

    public static final String MASK = "***";

    public record Result(String text, boolean redacted) {}

    private record Rule(Pattern pattern, String replacement) {}

    private static final String QUOTED_OR_BARE = "(?:\"[^\"]*\"|'[^']*'|[^\\s'\"&;|]+)";

    private static final List<Rule> RULES = List.of(
            // -----BEGIN ... PRIVATE KEY----- blocks
            new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"), MASK),
            // scheme://user:password@host
            new Rule(Pattern.compile("(?i)\\b([a-z][a-z0-9+.\\-]*://[^/\\s:@]+):[^/\\s@]+@"), "$1:***@"),
            // Authorization: Bearer|Basic|Token value
            new Rule(Pattern.compile("(?i)\\b(authorization:\\s*(?:bearer|basic|token)\\s+)[^\\s'\"]+"), "$1***"),
            // "password": "value" in inline JSON
            new Rule(Pattern.compile(
                    "(?i)(\"(?:password|passwd|secret|token|api_?key|access_?token|client_?secret)\"\\s*:\\s*\")[^\"]*\""),
                    "$1***\""),
            // --password value, --token=value, ...
            new Rule(Pattern.compile("(?i)(--(?:password|passwd|pass|token|api-?key|secret|access-token|auth-token"
                    + "|client-secret)(?:=|\\s+))" + QUOTED_OR_BARE), "$1***"),
            // NAME=value where NAME looks secret: *PASSWORD*, *SECRET*, *TOKEN*, *APIKEY*, *_KEY*, *_PASS*
            new Rule(Pattern.compile("(?i)\\b((?:[a-z0-9_]*(?:password|passwd|secret|token|apikey)|(?:[a-z0-9_]*_)?"
                    + "(?:key|pass))[a-z0-9_]*)(\\s*=\\s*)" + QUOTED_OR_BARE), "$1$2***"),
            // mysql -pSECRET (attached form only; case-sensitive so -P port is untouched)
            new Rule(Pattern.compile("\\b((?:mysql|mariadb|mysqldump|mysqladmin)\\b[^;&|\\n]*?\\s-p)(?=\\S)[^\\s;&|]+"),
                    "$1***"),
            // JWT-shaped strings
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), MASK),
            // AWS access key ids
            new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"), MASK),
            // ssh public key blobs
            new Rule(Pattern.compile(
                    "\\b(ssh-(?:rsa|ed25519|dss)|ecdsa-sha2-nistp(?:256|384|521))\\s+[A-Za-z0-9+/]{20,}={0,3}"),
                    "$1 ***"),
            // well-known token formats: GitHub, GitLab, Slack, OpenAI-style
            new Rule(Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"
                    + "|glpat-[A-Za-z0-9_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9_-]{20,})"), MASK));

    public Result mask(String input) {
        if (input == null || input.isEmpty()) {
            return new Result(input, false);
        }
        String text = input;
        for (Rule rule : RULES) {
            text = rule.pattern().matcher(text).replaceAll(rule.replacement());
        }
        return new Result(text, !text.equals(input));
    }
}
