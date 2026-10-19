import { Answer, describeProvenance } from './answer';

/** What the panel shows: the answer to a search, or the fix offered for a failed command. */
export interface PanelState {
  kind?: 'search' | 'fix';
  query: string;
  answer: Answer | null;
  error?: string;
}

export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const BADGE: Record<Answer['source'], string> = {
  TEAM: 'From your team',
  NONE: 'No match',
};

/**
 * The whole webview document for one result. Pure, so it is unit-tested; the only script is the nonce'd click
 * handler that posts {action} messages back to the extension.
 */
export function renderPanelHtml(state: PanelState, nonce: string, cspSource: string, now: Date): string {
  const isFix = state.kind === 'fix';
  const body = state.error ? renderError(state.error) : renderAnswer(state.answer, now, isFix);
  const heading = isFix ? 'This failed' : 'You asked';
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Recall</title>
<style nonce="${nonce}">
  body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 12px 16px; }
  .query { color: var(--vscode-descriptionForeground); margin-bottom: 12px; }
  .card { border: 1px solid var(--vscode-panel-border); border-radius: 6px; padding: 12px 14px; max-width: 760px; }
  .badge { display: inline-block; font-size: 11px; padding: 1px 8px; border-radius: 10px; margin-bottom: 8px;
           background: var(--vscode-badge-background); color: var(--vscode-badge-foreground); }
  pre.command { font-family: var(--vscode-editor-font-family); font-size: var(--vscode-editor-font-size);
                background: var(--vscode-textCodeBlock-background); padding: 10px 12px; border-radius: 4px;
                white-space: pre-wrap; word-break: break-all; margin: 0 0 8px; }
  .provenance { font-weight: 600; margin-bottom: 4px; }
  .explanation { color: var(--vscode-descriptionForeground); margin-bottom: 12px; }
  .actions button { font-family: inherit; margin-right: 8px; padding: 4px 12px; border: none; border-radius: 2px; cursor: pointer;
                    background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
  .actions button.primary { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
  .error { color: var(--vscode-errorForeground); }
</style>
</head>
<body>
<div class="query">${heading}: <strong>${escapeHtml(state.query)}</strong></div>
${body}
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  document.querySelectorAll('button[data-action]').forEach((b) =>
    b.addEventListener('click', () => vscode.postMessage({ action: b.dataset.action })));
</script>
</body>
</html>`;
}

function renderAnswer(answer: Answer | null, now: Date, isFix: boolean): string {
  if (!answer || answer.source === 'NONE' || !answer.command) {
    const why = answer?.explanation ?? 'Nobody on your team has run anything like this yet.';
    return `<div class="card">
  <span class="badge NONE">${BADGE.NONE}</span>
  <div class="explanation">${escapeHtml(why)}</div>
  ${isFix ? '' : '<div class="actions"><button data-action="again" class="primary">Search again</button></div>'}
</div>`;
  }
  const provenance = describeProvenance(answer.provenance, now);
  const confidence = answer.confidence !== null ? ` · match ${Math.round(answer.confidence * 100)}%` : '';
  // A stored fix can be reported as not working; a search answer can only be searched again.
  const reject = answer.fixId ? "Didn't work" : 'Not what I wanted';
  return `<div class="card">
  <span class="badge ${answer.source}">${BADGE[answer.source]}</span>
  <pre class="command">${escapeHtml(answer.command)}</pre>
  ${provenance ? `<div class="provenance">${escapeHtml(provenance)}${confidence}</div>` : ''}
  ${answer.explanation ? `<div class="explanation">${escapeHtml(answer.explanation)}</div>` : ''}
  <div class="actions">
    <button data-action="insert" class="primary">Insert into terminal</button>
    <button data-action="copy">Copy</button>
    ${isFix && !answer.fixId ? '' : `<button data-action="reject">${escapeHtml(reject)}</button>`}
  </div>
</div>`;
}

function renderError(message: string): string {
  return `<div class="card">
  <div class="error">${escapeHtml(message)}</div>
  <div class="actions"><button data-action="again" class="primary">Try again</button></div>
</div>`;
}
