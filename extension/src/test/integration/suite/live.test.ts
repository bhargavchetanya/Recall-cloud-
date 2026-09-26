import * as assert from 'assert';
import { execFileSync } from 'child_process';
import { randomUUID } from 'crypto';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { activateRecall, cardText, run, setServerUrl, terminalWithShellIntegration, waitFor } from './helpers';

/**
 * Only runs when RECALL_LIVE_SERVER points at a running backend (see index.ts). Creates a fresh team, signs in
 * through the extension and exercises it against the real server. Prints ids so the rows can be checked with psql.
 */
describe('Recall against a live backend', () => {
  const server = () => process.env.RECALL_LIVE_SERVER!;
  let owner: { email: string; teamId: string; inviteCode: string };

  async function post<T>(path: string, body: unknown, token?: string): Promise<{ status: number; body: T }> {
    const res = await fetch(`${server()}/api/v1${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    });
    return { status: res.status, body: (await res.json()) as T };
  }

  before(async () => {
    const email = `live-${randomUUID()}@example.com`;
    const res = await post<{ teamId: string; inviteCode: string }>('/auth/register', {
      teamName: 'Live demo',
      email,
      password: 'correct-horse',
      displayName: 'Live Tester',
    });
    assert.strictEqual(res.status, 201);
    owner = { email, ...res.body };
    await setServerUrl(server());
    assert.strictEqual(
      await vscode.commands.executeCommand('recall.signIn', { mode: 'login', email, password: 'correct-horse' }),
      true,
    );
  });

  it('sends real terminal commands to the backend', async function () {
    this.timeout(120_000);
    const api = await activateRecall();

    const terminal = await terminalWithShellIntegration('recall-live');
    await run(terminal, 'git --version');
    await run(terminal, 'git definitely-not-a-command');
    await run(terminal, 'echo mysql -u root -pHunter2');
    terminal.dispose();

    await api.flush();
    await waitFor('queue drained to the live backend', () => api.queueSize() === 0);
    console.log(`LIVE_TEAM_ID=${owner.teamId}`);
  });

  it('finds the one command a teammate used, from a question in plain words', async function () {
    this.timeout(120_000);
    // Daksh joins with the invite code; his history reaches the server the way his extension would send it.
    const daksh = await post<{ token: string }>('/auth/join', {
      inviteCode: owner.inviteCode,
      email: `daksh-${randomUUID()}@example.com`,
      password: 'correct-horse',
      displayName: 'Daksh',
    });
    assert.strictEqual(daksh.status, 201);
    const history = ['git status', 'git add -A', 'git commit -m "wip"', 'git reset --soft HEAD~1', 'npm test',
      'docker compose up -d', 'git reset --soft HEAD~1', 'kubectl get pods -n staging'];
    const session = randomUUID();
    const events = history.map((commandText, i) => ({
      sessionId: session, seq: i + 1, commandText, cwd: '/home/daksh/shop', repoName: 'shop', shell: 'bash',
      os: 'linux', exitCode: 0, startedAt: new Date(Date.now() - (history.length - i) * 60_000).toISOString(),
      durationMs: 120,
    }));
    assert.strictEqual((await post('/events', { events }, daksh.body.token)).status, 202);

    const api = await activateRecall();
    const question = 'undo my last commit but keep the changes';
    const answer = await pollSearch(question);

    assert.strictEqual(answer.command, 'git reset --soft HEAD~1');
    assert.strictEqual(answer.source, 'TEAM');
    assert.strictEqual(answer.provenance?.solvedBy, 'Daksh');
    assert.strictEqual(answer.provenance?.timesUsed, 2);
    const html = api.resultHtml();
    assert.ok(html.includes('<pre class="command">git reset --soft HEAD~1</pre>'));

    const card = cardText(html);
    console.log(`LIVE_SEARCH question="${question}"`);
    console.log(`LIVE_SEARCH answer=${JSON.stringify(answer)}`);
    console.log(`LIVE_SEARCH card="${card}"`);
  });

  it('learns a fix from a real failure in one terminal and offers it when the failure happens again', async function () {
    this.timeout(180_000);
    const api = await activateRecall();
    // Two throwaway repositories, so nothing in the user's own repos is touched.
    const base = path.join(os.tmpdir(), `recall-live-${randomUUID().slice(0, 8)}`);
    const first = path.join(base, 'first');
    const second = path.join(base, 'second');
    for (const dir of [first, second]) {
      fs.mkdirSync(dir, { recursive: true });
      execFileSync('git', ['init', '-q'], { cwd: dir });
    }

    const t1 = await terminalWithShellIntegration('recall-live-fail', first);
    const failedExit = await run(t1, 'git switch recall-demo');
    assert.notStrictEqual(failedExit, 0);
    assert.strictEqual(await run(t1, 'git switch -c recall-demo'), 0);
    // Leave the folder so the shell does not keep it locked on Windows after the terminal closes.
    await run(t1, `cd "${os.tmpdir()}"`);
    t1.dispose();
    await api.flush();

    // Wait until the consumer has learned it (the server answers the same error with the fix).
    const login = await post<{ token: string }>('/auth/login', { email: owner.email, password: 'correct-horse' });
    const learned = await waitForAsync(async () => {
      const r = await post<{ source: string; command: string }>('/errors/resolve',
        { commandText: 'git switch recall-demo', errorText: 'fatal: invalid reference: recall-demo', exitCode: 1 },
        login.body.token);
      return r.body.source === 'TEAM' ? r.body : undefined;
    });
    assert.strictEqual(learned.command, 'git switch -c recall-demo');

    const t2 = await terminalWithShellIntegration('recall-live-again', second);
    await run(t2, 'git switch recall-demo');
    await run(t2, `cd "${os.tmpdir()}"`);
    t2.dispose();
    const offered = await waitFor('the fix to be offered in the second terminal', () => {
      const fix = api.lastFix();
      return fix?.failedCommand === 'git switch recall-demo' && fix.answer.command ? fix : undefined;
    });
    assert.strictEqual(offered.answer.command, 'git switch -c recall-demo');

    await vscode.commands.executeCommand('recall.showLastFix');
    const card = cardText(api.resultHtml());
    console.log(`LIVE_FIX first terminal: git switch recall-demo (exit ${failedExit}) → git switch -c recall-demo (exit 0)`);
    console.log(`LIVE_FIX second terminal error: ${offered.errorText.split(/\r?\n/)[0]}`);
    console.log(`LIVE_FIX offered: ${JSON.stringify(offered.answer)}`);
    console.log(`LIVE_FIX card="${card}"`);
    // The throwaway repos are removed by runTest.ts once VS Code has exited (a running VS Code keeps them locked).
  });

  async function waitForAsync<T>(check: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const value = await check();
      if (value) {
        return value;
      }
      if (Date.now() > deadline) {
        throw new Error('Timed out waiting for the backend to learn the fix');
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
  }

  /** The consumer stores events asynchronously, so ask until the answer appears (or give up after 30 s). */
  async function pollSearch(question: string) {
    type Answer = { command: string; source: string; provenance: { solvedBy: string; timesUsed: number } | null };
    const deadline = Date.now() + 30_000;
    for (;;) {
      const answer = await vscode.commands.executeCommand<Answer>('recall.search', question);
      if (answer?.source === 'TEAM' || Date.now() > deadline) {
        return answer;
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
  }
});
