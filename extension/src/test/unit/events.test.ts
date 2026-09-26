import { describe, expect, it } from 'vitest';
import { CaptureSettings, FinishedExecution, MAX_COMMAND_CHARS, buildEvent, stripAnsi } from '../../events';
import { compilePatterns } from '../../masking';

const settings: CaptureSettings = { enabled: true, captureOutput: true, ignorePatterns: [], redactPatterns: [] };

function exec(overrides: Partial<FinishedExecution> = {}): FinishedExecution {
  return {
    sessionId: 'session-1',
    seq: 3,
    commandLine: 'npm run dev',
    cwd: '/home/dev/app',
    repoName: 'app',
    shell: 'bash',
    os: 'linux',
    exitCode: 0,
    startedAt: new Date('2026-09-01T10:00:00.000Z'),
    endedAt: new Date('2026-09-01T10:00:01.250Z'),
    output: 'ready on http://localhost:3000',
    osUser: 'ada',
    ...overrides,
  };
}

describe('buildEvent', () => {
  it('builds a successful event without output', () => {
    expect(buildEvent(exec(), settings)).toEqual({
      sessionId: 'session-1',
      seq: 3,
      commandText: 'npm run dev',
      redacted: false,
      cwd: '/home/dev/app',
      repoName: 'app',
      shell: 'bash',
      os: 'linux',
      exitCode: 0,
      startedAt: '2026-09-01T10:00:00.000Z',
      durationMs: 1250,
      osUser: 'ada',
    });
  });

  it('attaches cleaned, masked output for failures', () => {
    const event = buildEvent(
      exec({
        exitCode: 1,
        output: '\x1b]633;C\x07\x1b[31mError: listen EADDRINUSE :::3000\x1b[0m\r\nDATABASE_URL=postgres://a:pw@db/x\r\n',
      }),
      settings,
    );
    expect(event?.errorText).toBe('Error: listen EADDRINUSE :::3000\nDATABASE_URL=postgres://a:***@db/x');
    expect(event?.redacted).toBe(true);
  });

  it('keeps only the last 8 KB of output', () => {
    const event = buildEvent(exec({ exitCode: 2, output: 'x'.repeat(20_000) + 'THE END' }), settings);
    expect(event?.errorText).toHaveLength(8192);
    expect(event?.errorText?.endsWith('THE END')).toBe(true);
  });

  it('omits output when capture of output is off', () => {
    expect(buildEvent(exec({ exitCode: 1, output: 'boom' }), { ...settings, captureOutput: false })?.errorText)
      .toBeUndefined();
  });

  it('omits output that is only escape codes', () => {
    expect(buildEvent(exec({ exitCode: 1, output: '\x1b[0m\r\n' }), settings)?.errorText).toBeUndefined();
  });

  it('records an unknown exit code as null and attaches no output', () => {
    const event = buildEvent(exec({ exitCode: undefined, output: 'something' }), settings);
    expect(event?.exitCode).toBeNull();
    expect(event?.errorText).toBeUndefined();
  });

  it('masks the command and sets redacted', () => {
    const event = buildEvent(exec({ commandLine: 'mysql -u root -pHunter2' }), settings);
    expect(event?.commandText).toBe('mysql -u root -p***');
    expect(event?.redacted).toBe(true);
  });

  it('applies user redact patterns', () => {
    const redactPatterns = compilePatterns(['corp-[a-z]+']).patterns;
    expect(buildEvent(exec({ commandLine: 'login corp-secret' }), { ...settings, redactPatterns })?.commandText)
      .toBe('login ***');
  });

  it('returns null when capture is disabled', () => {
    expect(buildEvent(exec(), { ...settings, enabled: false })).toBeNull();
  });

  it('returns null for empty commands and commands starting with a space', () => {
    expect(buildEvent(exec({ commandLine: '   ' }), settings)).toBeNull();
    expect(buildEvent(exec({ commandLine: ' export SECRET_THING=1' }), settings)).toBeNull();
  });

  it('returns null for commands matching an ignore pattern, before any masking', () => {
    const ignorePatterns = compilePatterns(['^vault ', 'Hunter2']).patterns;
    expect(buildEvent(exec({ commandLine: 'vault kv get secret/db' }), { ...settings, ignorePatterns })).toBeNull();
    expect(buildEvent(exec({ commandLine: 'mysql -pHunter2' }), { ...settings, ignorePatterns })).toBeNull();
    expect(buildEvent(exec(), { ...settings, ignorePatterns })).not.toBeNull();
  });

  it('trims trailing newlines and caps very long commands', () => {
    expect(buildEvent(exec({ commandLine: 'ls -la\r\n' }), settings)?.commandText).toBe('ls -la');
    expect(buildEvent(exec({ commandLine: 'echo ' + 'a'.repeat(10_000) }), settings)?.commandText)
      .toHaveLength(MAX_COMMAND_CHARS);
  });

  it('never reports a negative duration', () => {
    const event = buildEvent(exec({ endedAt: new Date('2026-09-01T09:59:59.000Z') }), settings);
    expect(event?.durationMs).toBe(0);
  });
});

describe('stripAnsi', () => {
  it('removes colours, OSC marks and carriage returns', () => {
    expect(stripAnsi('\x1b]633;A\x07\x1b[1;32mok\x1b[0m\r\n\x1b]0;title\x1b\\done')).toBe('ok\ndone');
  });
});
