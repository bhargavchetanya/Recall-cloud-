import * as assert from 'assert';
import { execFileSync } from 'child_process';
import { randomUUID } from 'crypto';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { activateRecall, cardText, run, setServerUrl, terminalWithShellIntegration, waitFor } from './helpers';

/**
 * docs/DEMO.md, step by step, against the seeded docker-compose backend. Runs only with RECALL_DEMO_INVITE set
 * (the invite code printed by scripts/seed-demo.sh). Every step prints "DEMO <step>: ..." so the walkthrough can be
 * checked against what really happens. Commands are what a person would click or type.
 */
describe('docs/DEMO.md walkthrough', () => {
  const server = () => process.env.RECALL_LIVE_SERVER ?? 'http://localhost:8080';
  const repoRoot = path.resolve(__dirname, '../../../../..');
  const base = path.join(os.tmpdir(), `recall-live-demo-${randomUUID().slice(0, 8)}`);
  const say = (step: string, text: string) => console.log(`DEMO ${step}: ${text}`);

  before(() => fs.mkdirSync(base, { recursive: true }));

  it('1. joins the demo team with the invite code', async function () {
    this.timeout(60_000);
    await setServerUrl(server());
    const api = await activateRecall();
    const joined = await vscode.commands.executeCommand<boolean>('recall.signIn', {
      mode: 'join',
      inviteCode: process.env.RECALL_DEMO_INVITE,
      email: `you-${randomUUID().slice(0, 8)}@recall.demo`,
      password: 'recall-demo-2026',
      displayName: 'You',
    });
    assert.strictEqual(joined, true);
    await api.refreshHealth();
    say('1', `signed in; status bar shows "${api.statusText()}"`);
    assert.strictEqual(api.statusText(), 'Recall ●');
  });

  it('2. searches in plain words', async function () {
    this.timeout(60_000);
    const api = await activateRecall();
    for (const [question, expected] of [
      ['undo my last commit but keep the changes', 'git reset --soft HEAD~1'],
      ['free up disk space used by docker', 'docker system prune -af'],
    ]) {
      const answer = await vscode.commands.executeCommand<{ command: string; source: string }>('recall.search', question);
      say('2', `"${question}" → ${cardText(api.resultHtml()).replace(/^You asked: .*? From your team /, '')}`);
      assert.strictEqual(answer?.command, expected);
      assert.strictEqual(answer?.source, 'TEAM');
    }
  });

  it('3. gets a teammate\'s fix when a command fails', async function () {
    this.timeout(90_000);
    const api = await activateRecall();
    const t = await terminalWithShellIntegration('demo', base);
    await run(t, 'git init demo-a');
    await run(t, 'cd demo-a');
    const previous = api.lastFix();
    const exit = await run(t, 'git switch recall-demo');
    const offered = await waitFor('the fix notification', () => {
      const fix = api.lastFix();
      return fix !== previous && fix?.failedCommand === 'git switch recall-demo' ? fix : undefined;
    });
    say('3', `git switch recall-demo → exit ${exit}; notification: "Recall: a teammate fixed this — ${offered.answer.command}"`);
    await vscode.commands.executeCommand('recall.showLastFix');
    say('3', `Why? → ${cardText(api.resultHtml())}`);
    assert.strictEqual(offered.answer.command, 'git switch -c recall-demo');
    await vscode.commands.executeCommand('recall.insertCommand', offered.answer.command);
    t.sendText('', true);
    await new Promise((r) => setTimeout(r, 1500));
    await run(t, `cd "${base}"`);
    t.dispose();
  });

  it('4. teaches the team a new fix', async function () {
    this.timeout(120_000);
    const api = await activateRecall();
    const first = await terminalWithShellIntegration('demo-first', base);
    await run(first, 'git init demo-b');
    await run(first, 'cd demo-b');
    const failed = await run(first, 'git checkout feature/lab');
    await new Promise((r) => setTimeout(r, 1500));
    const before = api.lastFix();
    say('4', `git checkout feature/lab → exit ${failed}; no notification (nobody has fixed this yet)`);
    assert.notStrictEqual(before?.failedCommand, 'git checkout feature/lab',
      'this team already learned the fix in an earlier demo run; reset the demo data (docs/DEMO.md, "Rehearsing")');
    await run(first, 'git checkout -b feature/lab');
    await run(first, `cd "${base}"`);
    first.dispose();
    await api.flush();
    await new Promise((r) => setTimeout(r, 3000));

    const second = await terminalWithShellIntegration('demo-second', base);
    await run(second, 'git init demo-c');
    await run(second, 'cd demo-c');
    const previous = api.lastFix();
    await run(second, 'git checkout feature/lab');
    const learned = await waitFor('the learned fix', () => {
      const fix = api.lastFix();
      return fix !== previous && fix?.failedCommand === 'git checkout feature/lab' ? fix : undefined;
    }, 45_000);
    await vscode.commands.executeCommand('recall.showLastFix');
    say('4', `same error in a new repo → "${learned.answer.command}"; ${cardText(api.resultHtml())}`);
    assert.strictEqual(learned.answer.command, 'git checkout -b feature/lab');
    await run(second, `cd "${base}"`);
    second.dispose();
  });

  it('5. never sends secrets', async function () {
    this.timeout(60_000);
    const api = await activateRecall();
    const t = await terminalWithShellIntegration('demo-secret', base);
    await run(t, 'echo mysql -u root -pHunter2 shop');
    t.dispose();
    await api.flush();
    await new Promise((r) => setTimeout(r, 3000));
    const stored = execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'recall', '-d', 'recall',
      '-tAc', "select command_text || ' | redacted=' || redacted from command_events where command_text like 'echo mysql%' order by created_at desc limit 1"],
    { cwd: repoRoot }).toString().trim();
    say('5', `stored in Postgres: ${stored}`);
    assert.strictEqual(stored, 'echo mysql -u root -p*** shop | redacted=true');
  });

  it('6. keeps working offline and catches up', async function () {
    this.timeout(240_000);
    const api = await activateRecall();
    execFileSync('docker', ['compose', 'stop', 'backend'], { cwd: repoRoot });
    await api.refreshHealth();
    const t = await terminalWithShellIntegration('demo-offline', base);
    await run(t, 'git --version');
    await run(t, 'git status');
    t.dispose();
    await waitFor('events to queue up', () => api.queueSize() >= 2);
    say('6', `backend stopped; status bar shows "${api.statusText()}"`);
    execFileSync('docker', ['compose', 'start', 'backend'], { cwd: repoRoot });
    const deadline = Date.now() + 180_000;
    while (!(await api.refreshHealth()) && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 2000));
    }
    await waitFor('the queue to drain', () => api.queueSize() === 0, 30_000);
    say('6', `backend started; status bar shows "${api.statusText()}", queue empty`);
    assert.strictEqual(api.statusText(), 'Recall ●');
  });
});
