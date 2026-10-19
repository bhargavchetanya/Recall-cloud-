import { describe, expect, it } from 'vitest';
import { normalizeServerUrl } from '../../serverUrl';

describe('normalizeServerUrl', () => {
  it.each([
    ['https://3-91-20-7.sslip.io', 'https://3-91-20-7.sslip.io'],
    ['https://3-91-20-7.sslip.io/', 'https://3-91-20-7.sslip.io'],
    ['  https://3-91-20-7.sslip.io  ', 'https://3-91-20-7.sslip.io'],
    ['3-91-20-7.sslip.io', 'https://3-91-20-7.sslip.io'],
    ['https://3-91-20-7.sslip.io/api/v1/health', 'https://3-91-20-7.sslip.io'],
    ['https://3-91-20-7.sslip.io/api', 'https://3-91-20-7.sslip.io'],
    ['3.91.20.7', 'http://3.91.20.7'],
    ['http://3.91.20.7/', 'http://3.91.20.7'],
    ['localhost:8080', 'http://localhost:8080'],
    ['http://localhost:8080/api/v1/health', 'http://localhost:8080'],
    ['https://example.com/recall/', 'https://example.com/recall'],
  ])('%s → %s', (input, expected) => {
    expect(normalizeServerUrl(input)).toBe(expected);
  });

  it.each(['', '   ', 'ftp://example.com', 'https://', 'not a url at all'])('rejects %j', (input) => {
    expect(normalizeServerUrl(input)).toBeUndefined();
  });
});
