import * as fs from 'fs';
import * as path from 'path';
import { describe, expect, it } from 'vitest';
import { compilePatterns, maskSecrets, matchesAny } from '../../masking';

interface Case {
  note: string;
  input: string;
  expected: string;
}

// Same file the backend's SecretMaskerTest reads, so both sides stay in lockstep.
const cases: Case[] = JSON.parse(
  fs.readFileSync(path.resolve(__dirname, '../../../../shared/fixtures/masking-cases.json'), 'utf8'),
);

describe('maskSecrets with the shared fixture', () => {
  it('has at least 25 cases', () => {
    expect(cases.length).toBeGreaterThanOrEqual(25);
  });

  it.each(cases)('$note', ({ input, expected }) => {
    expect(maskSecrets(input)).toEqual({ text: expected, redacted: input !== expected });
  });
});

describe('maskSecrets', () => {
  it('leaves empty input alone', () => {
    expect(maskSecrets('')).toEqual({ text: '', redacted: false });
  });

  it('is idempotent', () => {
    const once = maskSecrets('PGPASSWORD=x mysql -pHunter2 --token abc').text;
    expect(maskSecrets(once)).toEqual({ text: once, redacted: false });
  });

  it('applies user redact patterns after the built-in rules', () => {
    const { patterns } = compilePatterns(['acme-[0-9]{6}']);
    expect(maskSecrets('deploy --license acme-123456 --token t0k', patterns)).toEqual({
      text: 'deploy --license *** --token ***',
      redacted: true,
    });
  });

  it('can reuse a compiled global pattern across calls', () => {
    const { patterns } = compilePatterns(['internal-\\d+']);
    expect(maskSecrets('a internal-1', patterns).text).toBe('a ***');
    expect(maskSecrets('b internal-2', patterns).text).toBe('b ***');
  });
});

describe('compilePatterns', () => {
  it('reports invalid expressions instead of throwing and skips blanks', () => {
    const result = compilePatterns(['ok\\d', '(unclosed', '']);
    expect(result.patterns).toHaveLength(1);
    expect(result.invalid).toEqual(['(unclosed']);
  });

  it('treats undefined as no patterns', () => {
    expect(compilePatterns(undefined)).toEqual({ patterns: [], invalid: [] });
  });
});

describe('matchesAny', () => {
  it('matches repeatedly with global patterns (lastIndex is reset)', () => {
    const { patterns } = compilePatterns(['^vault ']);
    expect(matchesAny('vault read secret/x', patterns)).toBe(true);
    expect(matchesAny('vault read secret/y', patterns)).toBe(true);
    expect(matchesAny('git status', patterns)).toBe(false);
  });
});
