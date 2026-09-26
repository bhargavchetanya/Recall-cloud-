/** The one answer the backend gives to a search or to an error. {@code fixId} is set only for stored fixes. */
export interface Answer {
  command: string | null;
  source: 'TEAM' | 'AI' | 'NONE';
  confidence: number | null;
  provenance: Provenance | null;
  explanation: string | null;
  fixId: string | null;
}

export interface Provenance {
  solvedBy: string | null;
  lastUsedAt: string | null;
  timesUsed: number;
}

/** Body of POST /errors/resolve. */
export interface ResolveRequest {
  commandText: string;
  errorText: string;
  exitCode: number;
  cwd?: string;
  repoName?: string;
  shell?: string;
  os?: string;
  osUser?: string;
}

export interface SearchRequest {
  query: string;
  cwd?: string;
  repoName?: string;
  shell?: string;
  os?: string;
}

const DAY = 86_400_000;

/** "just now", "3 hours ago", "yesterday", "last week", "2 months ago", ... */
export function relativeTime(when: Date, now: Date): string {
  const ms = Math.max(0, now.getTime() - when.getTime());
  const minutes = Math.floor(ms / 60_000);
  const hours = Math.floor(ms / 3_600_000);
  const days = Math.floor(ms / DAY);
  if (minutes < 1) {
    return 'just now';
  }
  if (hours < 1) {
    return minutes === 1 ? 'a minute ago' : `${minutes} minutes ago`;
  }
  if (days < 1) {
    return hours === 1 ? 'an hour ago' : `${hours} hours ago`;
  }
  if (days === 1) {
    return 'yesterday';
  }
  if (days < 7) {
    return `${days} days ago`;
  }
  if (days < 14) {
    return 'last week';
  }
  if (days < 30) {
    return `${Math.floor(days / 7)} weeks ago`;
  }
  if (days < 60) {
    return 'last month';
  }
  if (days < 365) {
    return `${Math.floor(days / 30)} months ago`;
  }
  return 'over a year ago';
}

/** "used 4× by Daksh, last week"; parts that are unknown are left out. */
export function describeProvenance(p: Provenance | null, now: Date): string {
  if (!p) {
    return '';
  }
  const times = p.timesUsed === 1 ? 'once' : `${p.timesUsed}×`;
  const who = p.solvedBy ? ` by ${p.solvedBy}` : '';
  const when = p.lastUsedAt ? `, ${relativeTime(new Date(p.lastUsedAt), now)}` : '';
  return `used ${times}${who}${when}`;
}

/** The notification shown when a failed command has a known fix; null when there is nothing to offer. */
export function fixNotificationText(answer: Answer): string | null {
  if (!answer.command || answer.source === 'NONE') {
    return null;
  }
  return answer.source === 'TEAM'
    ? `Recall: a teammate fixed this — ${answer.command}`
    : `Recall (unverified AI suggestion): ${answer.command}`;
}

const ERRORISH = /error|fatal|exception|denied|refused|not found|cannot|failed/i;

/** The first error-looking line of an error (else its first line), shortened for titles. */
export function errorHeadline(errorText: string, max = 120): string {
  const lines = errorText.split(/\r?\n/).map((l) => l.trim());
  const line = lines.find((l) => ERRORISH.test(l)) ?? lines.find((l) => l.length > 0) ?? '';
  return line.length > max ? `${line.slice(0, max - 1)}…` : line;
}
