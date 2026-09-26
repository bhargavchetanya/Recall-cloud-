import * as assert from 'assert';
import * as http from 'http';
import { AddressInfo } from 'net';
import * as vscode from 'vscode';

export interface RecallApi {
  statusText(): string;
  refreshHealth(): Promise<boolean>;
  queueSize(): number;
  flush(): Promise<void>;
  resultHtml(): string;
  lastFix(): { failedCommand: string; errorText: string; answer: { command: string | null; fixId: string | null } } | undefined;
}

export interface PostedEvent {
  sessionId: string;
  seq: number;
  commandText: string;
  redacted: boolean;
  exitCode: number | null;
  errorText?: string;
  shell?: string;
  os: string;
}

export interface FakeBackend {
  url: string;
  events: PostedEvent[];
  authHeaders: string[];
  searches: { query: string; os?: string; cwd?: string }[];
  resolves: { commandText: string; errorText: string; exitCode: number; osUser?: string }[];
  feedback: { fixId: string; worked: boolean }[];
  close(): Promise<void>;
}

export async function activateRecall(): Promise<RecallApi> {
  const ext = vscode.extensions.getExtension<RecallApi>('recall-team.recall');
  assert.ok(ext, 'extension recall-team.recall is installed in the test host');
  return ext.activate();
}

/** A stand-in for the Recall backend that records what the extension sends. */
export function startFakeBackend(health = 'UP'): Promise<FakeBackend> {
  const events: PostedEvent[] = [];
  const authHeaders: string[] = [];
  const searches: FakeBackend['searches'] = [];
  const resolves: FakeBackend['resolves'] = [];
  const feedback: FakeBackend['feedback'] = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const json = (status: number, payload: unknown) => {
        res.writeHead(status, { 'content-type': 'application/json' });
        res.end(JSON.stringify(payload));
      };
      if (req.url === '/api/v1/health') {
        return json(200, { status: health, db: health, queue: health, embedder: 'NOT_CONFIGURED' });
      }
      if (req.url === '/api/v1/auth/login' && req.method === 'POST') {
        return json(200, { token: 'fake-jwt', expiresAt: '2099-01-01T00:00:00Z', teamId: 'team-1' });
      }
      if (req.url === '/api/v1/search' && req.method === 'POST') {
        const q = JSON.parse(body) as FakeBackend['searches'][number];
        searches.push(q);
        return json(200, {
          command: 'git reset --soft HEAD~1',
          source: 'TEAM',
          confidence: 0.649,
          provenance: { solvedBy: 'Daksh', lastUsedAt: new Date(Date.now() - 8 * 86_400_000).toISOString(), timesUsed: 4 },
          explanation: 'Closest match in your team\x27s history.',
          fixId: null,
        });
      }
      if (req.url === '/api/v1/errors/resolve' && req.method === 'POST') {
        resolves.push(JSON.parse(body));
        return json(200, {
          command: 'git fetch --prune',
          source: 'TEAM',
          confidence: 1,
          provenance: { solvedBy: 'Ada', lastUsedAt: new Date(Date.now() - 3_600_000).toISOString(), timesUsed: 3 },
          explanation: 'Ada fixed this exact error; it has worked 3 times.',
          fixId: 'fix-42',
        });
      }
      const feedbackMatch = /^\/api\/v1\/fixes\/([^/]+)\/feedback$/.exec(req.url ?? '');
      if (feedbackMatch && req.method === 'POST') {
        feedback.push({ fixId: decodeURIComponent(feedbackMatch[1]), ...JSON.parse(body) });
        res.writeHead(204).end();
        return;
      }
      if (req.url === '/api/v1/events' && req.method === 'POST') {
        authHeaders.push(String(req.headers.authorization));
        const parsed = JSON.parse(body) as { events: PostedEvent[] };
        events.push(...parsed.events);
        return json(202, { accepted: parsed.events.length });
      }
      res.writeHead(404).end();
    });
  });
  return new Promise((resolve) =>
    server.listen(0, '127.0.0.1', () =>
      resolve({
        url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`,
        events,
        authHeaders,
        searches,
        resolves,
        feedback,
        close: () => new Promise<void>((r) => server.close(() => r())),
      }),
    ),
  );
}

export async function setServerUrl(url: string): Promise<void> {
  await vscode.workspace.getConfiguration('recall').update('serverUrl', url, vscode.ConfigurationTarget.Global);
}

export async function waitFor<T>(what: string, check: () => T | undefined | false, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = check();
    if (value) {
      return value;
    }
    if (Date.now() > deadline) {
      throw new Error(`Timed out waiting for ${what}`);
    }
    await new Promise((r) => setTimeout(r, 200));
  }
}

/** The visible text of the result panel, for printing a transcript. */
export function cardText(html: string): string {
  return html
    .replace(/<style[\s\S]*?<\/style>|<script[\s\S]*?<\/script>|<head>[\s\S]*?<\/head>/g, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Opens a terminal and waits until VS Code's shell integration is active in it. */
export async function terminalWithShellIntegration(name: string, cwd?: string): Promise<vscode.Terminal> {
  const terminal = vscode.window.createTerminal({ name, cwd });
  terminal.show();
  await waitFor(`shell integration in "${name}"`, () => terminal.shellIntegration, 45_000);
  return terminal;
}

/** Runs a command through shell integration and resolves when it has finished. */
export function run(terminal: vscode.Terminal, command: string): Promise<number | undefined> {
  return new Promise((resolve) => {
    const execution = terminal.shellIntegration!.executeCommand(command);
    const sub = vscode.window.onDidEndTerminalShellExecution((e) => {
      if (e.execution === execution) {
        sub.dispose();
        resolve(e.exitCode);
      }
    });
  });
}
