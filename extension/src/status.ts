export interface StatusState {
  connected: boolean;
  queued: number;
  signedIn: boolean;
  capturing: boolean;
}

export interface StatusView {
  text: string;
  tooltip: string;
}

/** `Recall ●` when the backend answers, `Recall ○` otherwise; the queued count is shown whenever it is non-zero. */
export function renderStatus(s: StatusState): StatusView {
  const queuedText = s.queued > 0 ? ` (${s.queued} queued)` : '';
  const text = `Recall ${s.connected ? '●' : '○'}${queuedText}`;

  const lines = [s.connected ? 'Recall: connected' : 'Recall: offline'];
  if (s.queued > 0) {
    lines.push(`${s.queued} event${s.queued === 1 ? '' : 's'} waiting to send`);
  }
  if (!s.signedIn) {
    lines.push('Not signed in');
  }
  if (!s.capturing) {
    lines.push('Capture is paused');
  }
  lines.push('Click for Recall actions');
  return { text, tooltip: lines.join('\n') };
}
