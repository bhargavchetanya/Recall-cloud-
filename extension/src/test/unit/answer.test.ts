import { describe, expect, it } from 'vitest';
import { describeProvenance, errorHeadline, fixNotificationText, relativeTime } from '../../answer';

const now = new Date('2026-09-24T12:00:00Z');
const ago = (ms: number) => new Date(now.getTime() - ms);
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

describe('relativeTime', () => {
  it.each([
    [0, 'just now'],
    [30_000, 'just now'],
    [MIN, 'a minute ago'],
    [5 * MIN, '5 minutes ago'],
    [HOUR, 'an hour ago'],
    [3 * HOUR, '3 hours ago'],
    [DAY, 'yesterday'],
    [3 * DAY, '3 days ago'],
    [7 * DAY, 'last week'],
    [13 * DAY, 'last week'],
    [15 * DAY, '2 weeks ago'],
    [35 * DAY, 'last month'],
    [100 * DAY, '3 months ago'],
    [400 * DAY, 'over a year ago'],
  ])('%i ms ago is "%s"', (ms, expected) => {
    expect(relativeTime(ago(ms), now)).toBe(expected);
  });

  it('treats future timestamps (clock skew) as just now', () => {
    expect(relativeTime(new Date(now.getTime() + HOUR), now)).toBe('just now');
  });
});

describe('describeProvenance', () => {
  it('reads like the spec example', () => {
    expect(describeProvenance({ solvedBy: 'Daksh', lastUsedAt: ago(8 * DAY).toISOString(), timesUsed: 4 }, now))
      .toBe('used 4× by Daksh, last week');
  });

  it('says once for a single use', () => {
    expect(describeProvenance({ solvedBy: 'Ada', lastUsedAt: ago(2 * DAY).toISOString(), timesUsed: 1 }, now))
      .toBe('used once by Ada, 2 days ago');
  });

  it('leaves out unknown parts and handles no provenance', () => {
    expect(describeProvenance({ solvedBy: null, lastUsedAt: null, timesUsed: 2 }, now)).toBe('used 2×');
    expect(describeProvenance(null, now)).toBe('');
  });
});

describe('fixNotificationText', () => {
  const base = { confidence: 1, provenance: null, explanation: null, fixId: 'f1' };

  it('credits the team for team fixes', () => {
    expect(fixNotificationText({ ...base, command: 'npx kill-port 3000', source: 'TEAM' }))
      .toBe('Recall: a teammate fixed this — npx kill-port 3000');
  });

  it('labels AI suggestions as unverified', () => {
    expect(fixNotificationText({ ...base, command: 'chmod +x run.sh', source: 'AI', fixId: null }))
      .toBe('Recall (unverified AI suggestion): chmod +x run.sh');
  });

  it('has nothing to say for NONE', () => {
    expect(fixNotificationText({ ...base, command: null, source: 'NONE', fixId: null })).toBeNull();
  });
});

describe('errorHeadline', () => {
  it('picks the first error-looking line', () => {
    expect(errorHeadline('> app@1.0.0 dev\n> node server.js\r\nError: listen EADDRINUSE :::3000\n    at x'))
      .toBe('Error: listen EADDRINUSE :::3000');
  });

  it('falls back to the first non-empty line and shortens long lines', () => {
    expect(errorHeadline('\n  segmentation hmm\nmore')).toBe('segmentation hmm');
    expect(errorHeadline('error ' + 'x'.repeat(200), 20)).toBe('error xxxxxxxxxxxxx…');
    expect(errorHeadline('')).toBe('');
  });
});
