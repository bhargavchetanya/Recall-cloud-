/**
 * Secret masking, run before anything leaves the machine. The backend runs the same rules again
 * (backend/.../SecretMasker.java); both are tested against shared/fixtures/masking-cases.json,
 * so rule order and patterns must stay in step.
 */

export const MASK = '***';

export interface MaskResult {
  text: string;
  redacted: boolean;
}

const QUOTED_OR_BARE = String.raw`(?:"[^"]*"|'[^']*'|[^\s'"&;|]+)`;

const RULES: ReadonlyArray<readonly [RegExp, string]> = [
  // -----BEGIN ... PRIVATE KEY----- blocks
  [/-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g, MASK],
  // scheme://user:password@host
  [/\b([a-z][a-z0-9+.-]*:\/\/[^/\s:@]+):[^/\s@]+@/gi, '$1:***@'],
  // Authorization: Bearer|Basic|Token value
  [/\b(authorization:\s*(?:bearer|basic|token)\s+)[^\s'"]+/gi, '$1***'],
  // "password": "value" in inline JSON
  [/("(?:password|passwd|secret|token|api_?key|access_?token|client_?secret)"\s*:\s*")[^"]*"/gi, '$1***"'],
  // --password value, --token=value, ...
  [
    new RegExp(
      String.raw`(--(?:password|passwd|pass|token|api-?key|secret|access-token|auth-token|client-secret)(?:=|\s+))` +
        QUOTED_OR_BARE,
      'gi',
    ),
    '$1***',
  ],
  // NAME=value where NAME looks secret: *PASSWORD*, *SECRET*, *TOKEN*, *APIKEY*, *_KEY*, *_PASS*
  [
    new RegExp(
      String.raw`\b((?:[a-z0-9_]*(?:password|passwd|secret|token|apikey)|(?:[a-z0-9_]*_)?(?:key|pass))[a-z0-9_]*)(\s*=\s*)` +
        QUOTED_OR_BARE,
      'gi',
    ),
    '$1$2***',
  ],
  // mysql -pSECRET (attached form only; case-sensitive so -P port is untouched)
  [/\b((?:mysql|mariadb|mysqldump|mysqladmin)\b[^;&|\n]*?\s-p)(?=\S)[^\s;&|]+/g, '$1***'],
  // JWT-shaped strings
  [/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g, MASK],
  // AWS access key ids
  [/\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g, MASK],
  // ssh public key blobs
  [/\b(ssh-(?:rsa|ed25519|dss)|ecdsa-sha2-nistp(?:256|384|521))\s+[A-Za-z0-9+/]{20,}={0,3}/g, '$1 ***'],
  // well-known token formats: GitHub, GitLab, Slack, OpenAI-style
  [
    /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9_-]{20,})/g,
    MASK,
  ],
];

/** Applies the built-in rules, then each user pattern (whole match becomes ***). */
export function maskSecrets(input: string, userPatterns: readonly RegExp[] = []): MaskResult {
  if (!input) {
    return { text: input, redacted: false };
  }
  let text = input;
  for (const [pattern, replacement] of RULES) {
    text = text.replace(pattern, replacement);
  }
  for (const pattern of userPatterns) {
    text = text.replace(pattern, MASK);
  }
  return { text, redacted: text !== input };
}

export interface CompiledPatterns {
  patterns: RegExp[];
  invalid: string[];
}

/** Compiles user-supplied regex strings; broken ones are reported instead of throwing. */
export function compilePatterns(sources: readonly string[] | undefined): CompiledPatterns {
  const patterns: RegExp[] = [];
  const invalid: string[] = [];
  for (const source of sources ?? []) {
    if (!source) {
      continue;
    }
    try {
      patterns.push(new RegExp(source, 'g'));
    } catch {
      invalid.push(source);
    }
  }
  return { patterns, invalid };
}

export function matchesAny(text: string, patterns: readonly RegExp[]): boolean {
  return patterns.some((p) => {
    p.lastIndex = 0;
    return p.test(text);
  });
}
