import { maskSecrets, matchesAny } from './masking';

/** Matches the backend's limits: longer commands are rejected there, so they are cut here. */
export const MAX_COMMAND_CHARS = 8192;
export const MAX_ERROR_CHARS = 8192;

/** The JSON body of one event in POST /api/v1/events. */
export interface CommandEvent {
  sessionId: string;
  seq: number;
  commandText: string;
  redacted: boolean;
  cwd?: string;
  repoName?: string;
  shell?: string;
  os: string;
  exitCode: number | null;
  startedAt: string;
  durationMs: number;
  errorText?: string;
  /** Local account name; the server only uses it to normalize the name out of error text. */
  osUser?: string;
}

/** What the terminal watcher knows about one finished command. */
export interface FinishedExecution {
  sessionId: string;
  seq: number;
  commandLine: string;
  cwd?: string;
  repoName?: string;
  shell?: string;
  os: string;
  exitCode: number | undefined;
  startedAt: Date;
  endedAt: Date;
  output?: string;
  osUser?: string;
}

export interface CaptureSettings {
  enabled: boolean;
  captureOutput: boolean;
  ignorePatterns: RegExp[];
  redactPatterns: RegExp[];
}

// CSI (colours, cursor moves), OSC (titles, VS Code's 633 shell-integration marks) and lone escapes.
const ANSI = /\x1b\[[0-?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[@-Z\\-_]/g;

export function stripAnsi(text: string): string {
  return text.replace(ANSI, '').replace(/\r/g, '');
}

export function keepTail(text: string, max: number): string {
  return text.length <= max ? text : text.slice(text.length - max);
}

/**
 * Turns a finished execution into an event, or null when it must not be sent: capture is off, the command is
 * empty, it starts with a space (the shell convention for "keep out of history"), or it matches an ignore pattern.
 * Error output is attached only for failures, cleaned of escape codes, masked and cut to its last 8 KB.
 */
export function buildEvent(exec: FinishedExecution, settings: CaptureSettings): CommandEvent | null {
  if (!settings.enabled) {
    return null;
  }
  const raw = exec.commandLine.replace(/[\r\n]+$/, '');
  if (!raw.trim() || raw.startsWith(' ')) {
    return null;
  }
  if (matchesAny(raw, settings.ignorePatterns)) {
    return null;
  }

  const command = maskSecrets(raw.trim(), settings.redactPatterns);
  const event: CommandEvent = {
    sessionId: exec.sessionId,
    seq: exec.seq,
    commandText: command.text.slice(0, MAX_COMMAND_CHARS),
    redacted: command.redacted,
    cwd: exec.cwd,
    repoName: exec.repoName,
    shell: exec.shell,
    os: exec.os,
    exitCode: exec.exitCode ?? null,
    startedAt: exec.startedAt.toISOString(),
    durationMs: Math.max(0, exec.endedAt.getTime() - exec.startedAt.getTime()),
    osUser: exec.osUser,
  };

  const failed = exec.exitCode !== undefined && exec.exitCode !== 0;
  if (failed && settings.captureOutput && exec.output) {
    const cleaned = stripAnsi(exec.output).trim();
    if (cleaned) {
      const error = maskSecrets(cleaned, settings.redactPatterns);
      event.errorText = keepTail(error.text, MAX_ERROR_CHARS);
      event.redacted = event.redacted || error.redacted;
    }
  }
  return event;
}
