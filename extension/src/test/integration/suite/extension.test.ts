import * as assert from 'assert';
import * as vscode from 'vscode';
import {
  FakeBackend,
  activateRecall,
  run,
  setServerUrl,
  startFakeBackend,
  terminalWithShellIntegration,
  waitFor,
} from './helpers';

describe('Recall extension', () => {
  let backend: FakeBackend | undefined;

  afterEach(async () => {
    await backend?.close();
    backend = undefined;
  });

  it('activates and shows a connected status bar item when the backend is UP', async () => {
    backend = await startFakeBackend('UP');
    await setServerUrl(backend.url);
    const api = await activateRecall();

    assert.strictEqual(await api.refreshHealth(), true);
    assert.strictEqual(api.statusText(), 'Recall ●');
  });

  it('registers every command it contributes', async () => {
    await activateRecall();
    const ext = vscode.extensions.getExtension('recall-team.recall')!;
    const contributed = (ext.packageJSON.contributes.commands as { command: string }[]).map((c) => c.command);
    const registered = new Set(await vscode.commands.getCommands(true));
    for (const command of [...contributed, 'recall.insertCommand', 'recall.reportFixFailed']) {
      assert.ok(registered.has(command), `${command} is registered`);
    }
  });

  it('shows offline when the backend is unreachable', async () => {
    const closed = await startFakeBackend('UP');
    await closed.close();
    await setServerUrl(closed.url);
    const api = await activateRecall();

    assert.strictEqual(await api.refreshHealth(), false);
    assert.ok(api.statusText().startsWith('Recall ○'), api.statusText());
  });

  it('captures real terminal commands, masks them and sends them with the signed-in token', async function () {
    this.timeout(120_000);
    backend = await startFakeBackend('UP');
    await setServerUrl(backend.url);
    const api = await activateRecall();
    const signedIn = await vscode.commands.executeCommand<boolean>('recall.signIn', {
      mode: 'login',
      email: 'ada@example.com',
      password: 'correct-horse',
    });
    assert.strictEqual(signedIn, true);

    const terminal = await terminalWithShellIntegration('recall-it');
    assert.strictEqual(await run(terminal, 'echo recall-it-ok'), 0);
    const failedExit = await run(terminal, 'git definitely-not-a-command');
    await run(terminal, 'echo PGPASSWORD=hunter2');
    terminal.dispose();

    const fake = backend;
    const events = await waitFor('three events at the fake backend', () => {
      const mine = fake.events.filter((e) => /recall-it-ok|definitely-not-a-command|PGPASSWORD/.test(e.commandText));
      return mine.length >= 3 ? mine : undefined;
    });

    const [ok, failed, secret] = events;
    assert.strictEqual(ok.commandText, 'echo recall-it-ok');
    assert.strictEqual(ok.exitCode, 0);
    assert.strictEqual(ok.errorText, undefined);

    assert.strictEqual(failed.commandText, 'git definitely-not-a-command');
    assert.notStrictEqual(failed.exitCode, 0);
    assert.strictEqual(failed.exitCode, failedExit ?? null);
    assert.ok(failed.errorText?.includes('definitely-not-a-command'), `errorText was: ${failed.errorText}`);
    assert.ok(!failed.errorText?.includes('\x1b'), 'escape codes are stripped');

    assert.strictEqual(secret.commandText, 'echo PGPASSWORD=***');
    assert.strictEqual(secret.redacted, true);
    assert.ok(!JSON.stringify(fake.events).includes('hunter2'), 'the secret never left the machine');

    assert.strictEqual(new Set(events.map((e) => e.sessionId)).size, 1, 'one terminal, one session');
    assert.deepStrictEqual(events.map((e) => e.seq), [...events.map((e) => e.seq)].sort((a, b) => a - b));
    assert.strictEqual(ok.os, process.platform);
    assert.ok(fake.authHeaders.every((h) => h === 'Bearer fake-jwt'));
    await waitFor('empty queue', () => api.queueSize() === 0);
  });

  it('keeps events queued while the backend is down and delivers them when it returns', async function () {
    this.timeout(120_000);
    const down = await startFakeBackend('UP');
    const downUrl = down.url;
    await down.close();
    await setServerUrl(downUrl);
    const api = await activateRecall();

    const terminal = await terminalWithShellIntegration('recall-offline');
    await run(terminal, 'echo recall-offline-1');
    await run(terminal, 'echo recall-offline-2');
    terminal.dispose();
    await waitFor('two queued events', () => api.queueSize() >= 2);

    backend = await startFakeBackend('UP');
    await setServerUrl(backend.url);
    await api.flush();

    const fake = backend;
    await waitFor('queued events delivered', () =>
      fake.events.filter((e) => e.commandText.startsWith('echo recall-offline-')).length === 2,
    );
    assert.strictEqual(api.queueSize(), 0);
  });

  it('searches with the current context and renders one command with provenance', async () => {
    backend = await startFakeBackend('UP');
    await setServerUrl(backend.url);
    const api = await activateRecall();
    await vscode.commands.executeCommand('recall.signIn', { mode: 'login', email: 'ada@example.com', password: 'x' });

    const answer = await vscode.commands.executeCommand<{ command: string; source: string }>(
      'recall.search',
      'undo my last commit but keep the changes',
    );

    assert.strictEqual(answer?.command, 'git reset --soft HEAD~1');
    assert.strictEqual(backend.searches[0].query, 'undo my last commit but keep the changes');
    assert.strictEqual(backend.searches[0].os, process.platform);
    const html = api.resultHtml();
    assert.ok(html.includes('<pre class="command">git reset --soft HEAD~1</pre>'), html);
    assert.ok(html.includes('used 4× by Daksh, last week'), html);
    assert.ok(html.includes('data-action="insert"'));
  });

  it('insert puts the command in a terminal without running it', async function () {
    this.timeout(60_000);
    const terminal = await terminalWithShellIntegration('recall-insert');
    let ran = false;
    const sub = vscode.window.onDidStartTerminalShellExecution((e) => {
      if (e.terminal === terminal) {
        ran = true;
      }
    });
    await vscode.commands.executeCommand('recall.insertCommand', 'echo recall-inserted');
    await new Promise((r) => setTimeout(r, 1500));
    sub.dispose();
    assert.strictEqual(vscode.window.activeTerminal, terminal);
    assert.strictEqual(ran, false, 'inserted text must wait for the user to press Enter');
    terminal.dispose();
  });

  it('offers the team fix when a command fails and records negative feedback', async function () {
    this.timeout(120_000);
    backend = await startFakeBackend('UP');
    await setServerUrl(backend.url);
    const api = await activateRecall();
    await vscode.commands.executeCommand('recall.signIn', { mode: 'login', email: 'ada@example.com', password: 'x' });

    const terminal = await terminalWithShellIntegration('recall-fix');
    await run(terminal, 'git definitely-not-a-command');
    terminal.dispose();

    const fake = backend;
    const resolved = await waitFor('the failure to be looked up', () => fake.resolves[0]);
    assert.strictEqual(resolved.commandText, 'git definitely-not-a-command');
    assert.ok(resolved.errorText.includes('definitely-not-a-command'));
    assert.notStrictEqual(resolved.exitCode, 0);
    assert.ok(resolved.osUser, 'the OS user is sent for normalization');

    const offered = await waitFor('the fix to be offered', () => api.lastFix());
    assert.strictEqual(offered.answer.command, 'git fetch --prune');

    await vscode.commands.executeCommand('recall.showLastFix');
    const html = api.resultHtml();
    assert.ok(html.includes('This failed: <strong>git definitely-not-a-command'), html);
    assert.ok(html.includes('<pre class="command">git fetch --prune</pre>'));
    assert.ok(html.includes('used 3× by Ada, an hour ago'), html);
    assert.ok(html.includes('Didn&#39;t work'));

    await vscode.commands.executeCommand('recall.reportFixFailed', 'fix-42');
    assert.deepStrictEqual(fake.feedback, [{ fixId: 'fix-42', worked: false }]);
  });
});
