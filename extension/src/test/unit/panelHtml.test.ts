import { describe, expect, it } from 'vitest';
import type { Answer } from '../../answer';
import { escapeHtml, renderPanelHtml } from '../../panelHtml';

const now = new Date('2026-09-24T12:00:00Z');
const team: Answer = {
  command: 'git reset --soft HEAD~1',
  source: 'TEAM',
  confidence: 0.649,
  provenance: { solvedBy: 'Daksh', lastUsedAt: '2026-09-16T12:00:00Z', timesUsed: 4 },
  explanation: 'Closest match in your team\'s history (meaning 0.80, used 4×).',
  fixId: null,
};

const render = (answer: Answer | null, error?: string) =>
  renderPanelHtml({ query: 'undo my last commit', answer, error }, 'NONCE123', 'vscode-resource:', now);

describe('renderPanelHtml', () => {
  it('shows the one command, provenance, match and the three actions', () => {
    const html = render(team);
    expect(html).toContain('<pre class="command">git reset --soft HEAD~1</pre>');
    expect(html).toContain('used 4× by Daksh, last week · match 65%');
    expect(html).toContain('From your team');
    expect(html).toContain('data-action="insert"');
    expect(html).toContain('data-action="copy"');
    expect(html).toContain('data-action="reject"');
    expect(html).toContain('You asked: <strong>undo my last commit</strong>');
  });

  it('shows a no-match card with search again for NONE', () => {
    const html = render({ command: null, source: 'NONE', confidence: null, provenance: null,
      explanation: 'Nobody on your team has run anything like this yet.', fixId: null });
    expect(html).toContain('No match');
    expect(html).toContain('Nobody on your team has run anything like this yet.');
    expect(html).toContain('data-action="again"');
    expect(html).not.toContain('data-action="insert"');
  });

  it('shows errors', () => {
    const html = render(null, 'Recall could not search: HTTP 503');
    expect(html).toContain('class="error">Recall could not search: HTTP 503');
    expect(html).toContain('Try again');
  });

  it('escapes everything that comes from the server or the user', () => {
    const html = renderPanelHtml(
      { query: '<img src=x onerror=alert(1)>', answer: { ...team, command: 'echo "<script>alert(1)</script>"' } },
      'N', 'vscode-resource:', now);
    expect(html).not.toContain('<img src=x');
    expect(html).not.toContain('<script>alert(1)');
    expect(html).toContain('echo &quot;&lt;script&gt;alert(1)&lt;/script&gt;&quot;');
  });

  it('locks scripts and styles to the nonce with a strict CSP', () => {
    const html = render(team);
    expect(html).toContain("default-src 'none'; style-src vscode-resource: 'nonce-NONCE123'; script-src 'nonce-NONCE123';");
    expect(html).toContain('<script nonce="NONCE123">');
  });
});

describe('escapeHtml', () => {
  it('escapes the five special characters', () => {
    expect(escapeHtml(`&<>"'`)).toBe('&amp;&lt;&gt;&quot;&#39;');
  });
});

describe('renderPanelHtml for a fix', () => {
  const fix: Answer = { ...team, command: 'npx kill-port 3000', fixId: '7f1c', explanation: 'Ada fixed this exact error.' };

  it('says what failed and offers "Didn\'t work" for a stored fix', () => {
    const html = renderPanelHtml({ kind: 'fix', query: 'npm run dev → Error: listen EADDRINUSE', answer: fix },
      'N', 'vscode-resource:', now);
    expect(html).toContain('This failed: <strong>npm run dev → Error: listen EADDRINUSE</strong>');
    expect(html).toContain('<pre class="command">npx kill-port 3000</pre>');
    expect(html).toContain('>Didn&#39;t work</button>');
    expect(html).not.toContain('Not what I wanted');
  });
});
