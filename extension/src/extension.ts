import * as vscode from 'vscode';
import { RecallClient } from './api';
import { SignInArgs, TokenStore, signIn } from './auth';
import { CaptureSettings, buildEvent } from './events';
import { isBackendUp } from './health';
import { compilePatterns } from './masking';
import { menuItems } from './menu';
import { EventQueue } from './queue';
import { FixAdvisor, OfferedFix } from './fixes';
import { PanelAction, RecallPanel, insertIntoTerminal } from './panel';
import type { PanelState } from './panelHtml';
import { SearchController } from './search';
import { normalizeServerUrl } from './serverUrl';
import { renderStatus } from './status';
import { TerminalWatcher } from './watcher';

const HEALTH_POLL_MS = 30_000;

/** Returned from activate() so integration tests can observe the extension's state. */
export interface RecallApi {
  statusText(): string;
  refreshHealth(): Promise<boolean>;
  queueSize(): number;
  flush(): Promise<void>;
  resultHtml(): string;
  lastFix(): OfferedFix | undefined;
}

let queue: EventQueue | undefined;

export async function activate(context: vscode.ExtensionContext): Promise<RecallApi> {
  const log = vscode.window.createOutputChannel('Recall', { log: true });
  const statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  const tokens = new TokenStore(context.secrets);
  context.subscriptions.push(log, statusItem, tokens);

  const config = () => vscode.workspace.getConfiguration('recall');
  const serverUrl = () => config().get<string>('serverUrl', 'http://localhost:8080');
  const client = new RecallClient(serverUrl, () => tokens.get());

  let connected = false;
  let signedIn = !!(await tokens.get());
  const show = () => {
    const view = renderStatus({
      connected,
      queued: queue?.size() ?? 0,
      signedIn,
      capturing: config().get<boolean>('enabled', true),
    });
    statusItem.text = view.text;
    statusItem.tooltip = view.tooltip;
    statusItem.command = 'recall.menu';
  };

  queue = new EventQueue(context.globalState, (batch) => client.postEvents(batch), {}, () => show());

  let warnedPatterns = '';
  const captureSettings = (): CaptureSettings => {
    const ignore = compilePatterns(config().get<string[]>('ignorePatterns', []));
    const redact = compilePatterns(config().get<string[]>('redactPatterns', []));
    const invalid = [...ignore.invalid, ...redact.invalid].join(', ');
    if (invalid && invalid !== warnedPatterns) {
      warnedPatterns = invalid;
      log.warn(`Ignoring invalid regular expressions in Recall settings: ${invalid}`);
    }
    return {
      enabled: config().get<boolean>('enabled', true),
      captureOutput: config().get<boolean>('captureOutput', true),
      ignorePatterns: ignore.patterns,
      redactPatterns: redact.patterns,
    };
  };

  const panel = new RecallPanel((action, state) => void onPanelAction(action, state));
  context.subscriptions.push(panel);
  const search = new SearchController(client, panel, () => signedIn, log);
  const fixes = new FixAdvisor(client, panel, () => signedIn,
    () => config().get<boolean>('autoFixNotifications', true), log);

  const onPanelAction = async (action: PanelAction, state: PanelState): Promise<void> => {
    if (state.kind !== 'fix') {
      return search.handle(action, state);
    }
    const command = state.answer?.command;
    if (action === 'insert' && command) {
      await vscode.commands.executeCommand('recall.insertCommand', command);
    } else if (action === 'copy' && command) {
      await vscode.env.clipboard.writeText(command);
    } else if (action === 'reject' && state.answer?.fixId) {
      await vscode.commands.executeCommand('recall.reportFixFailed', state.answer.fixId);
    }
  };

  const watcher = new TerminalWatcher(
    (exec) => {
      const event = buildEvent(exec, captureSettings());
      if (event) {
        log.debug(`Captured "${event.commandText}" (exit ${event.exitCode ?? 'unknown'})`);
        queue?.enqueue(event);
        void fixes.onCommandFinished(event);
      }
    },
    () => captureSettings().captureOutput,
    log,
  );
  context.subscriptions.push(watcher);

  const refreshHealth = async (): Promise<boolean> => {
    const up = await isBackendUp(serverUrl(), fetch);
    if (up !== connected) {
      log.info(`Backend ${serverUrl()} is ${up ? 'reachable' : 'unreachable'}`);
    }
    connected = up;
    show();
    if (up && signedIn) {
      await queue?.resume();
    }
    return up;
  };

  context.subscriptions.push(
    tokens.onDidChange((hasToken) => {
      signedIn = hasToken;
      show();
      if (hasToken) {
        void queue?.resume();
      }
    }),
    vscode.commands.registerCommand('recall.signIn', (args?: SignInArgs) => signIn(client, tokens, args)),
    vscode.commands.registerCommand('recall.search', (query?: string) => search.run(query)),
    // Not in the Command Palette: used by the result panel's Insert button (and tests).
    vscode.commands.registerCommand('recall.insertCommand', (command: string) => void insertIntoTerminal(command)),
    vscode.commands.registerCommand('recall.showLastFix', () => {
      if (!fixes.showLast()) {
        void vscode.window.showInformationMessage('Recall has not offered a fix yet in this window.');
      }
    }),
    // Not in the Command Palette: used by the panel's "Didn't work" button (and tests).
    vscode.commands.registerCommand('recall.reportFixFailed', (fixId: string) => fixes.reportFailed(fixId)),
    vscode.commands.registerCommand('recall.menu', async () => {
      const items = menuItems({
        signedIn,
        connected,
        serverUrl: serverUrl(),
        capturing: config().get<boolean>('enabled', true),
        hasLastFix: !!fixes.lastFix(),
        queued: queue?.size() ?? 0,
      });
      const picked = await vscode.window.showQuickPick(items, { title: 'Recall', placeHolder: 'What do you want to do?' });
      if (picked) {
        await vscode.commands.executeCommand(picked.command);
      }
    }),
    vscode.commands.registerCommand('recall.setServerUrl', async (value?: string) => {
      const input = value ?? (await vscode.window.showInputBox({
        title: 'Recall server URL',
        prompt: 'The address of your Recall server, for example https://3-91-20-7.sslip.io',
        value: serverUrl(),
        ignoreFocusOut: true,
        validateInput: (v) => (normalizeServerUrl(v) ? undefined : 'Enter an http:// or https:// address'),
      }));
      if (input === undefined) {
        return;
      }
      const url = normalizeServerUrl(input);
      if (!url) {
        void vscode.window.showErrorMessage(`Recall: "${input}" is not an http:// or https:// address.`);
        return;
      }
      const changed = url !== serverUrl();
      await config().update('serverUrl', url, vscode.ConfigurationTarget.Global);
      // A login belongs to one server (each server signs its own tokens), so a new server needs a new sign-in.
      if (changed && signedIn) {
        await tokens.clear();
      }
      if (!(await refreshHealth())) {
        void vscode.window.showWarningMessage(`Recall: saved, but ${url} is not reachable yet.`);
      } else if (!signedIn) {
        const signInNow = 'Sign in';
        const picked = await vscode.window.showInformationMessage(`Recall: connected to ${url}.`, signInNow);
        if (picked === signInNow) {
          await vscode.commands.executeCommand('recall.signIn');
        }
      }
    }),
    vscode.commands.registerCommand('recall.showLog', () => log.show()),
    vscode.commands.registerCommand('recall.flush', () => queue?.resume()),
    vscode.commands.registerCommand('recall.signOut', async () => {
      await tokens.clear();
      void vscode.window.showInformationMessage('Recall: signed out. Captured commands stay queued until you sign in.');
    }),
    vscode.commands.registerCommand('recall.toggleCapture', async () => {
      const enabled = !config().get<boolean>('enabled', true);
      await config().update('enabled', enabled, vscode.ConfigurationTarget.Global);
      void vscode.window.showInformationMessage(`Recall capture ${enabled ? 'resumed' : 'paused'}.`);
    }),
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('recall.serverUrl')) {
        void refreshHealth();
      }
      if (e.affectsConfiguration('recall.enabled')) {
        show();
      }
    }),
  );

  const timer = setInterval(() => void refreshHealth(), HEALTH_POLL_MS);
  context.subscriptions.push({ dispose: () => clearInterval(timer) });

  show();
  statusItem.show();
  log.info('Recall activated');
  void refreshHealth();

  return {
    statusText: () => statusItem.text,
    refreshHealth,
    queueSize: () => queue?.size() ?? 0,
    flush: () => queue?.resume() ?? Promise.resolve(),
    resultHtml: () => panel.currentHtml(),
    lastFix: () => fixes.lastFix(),
  };
}

export async function deactivate(): Promise<void> {
  await queue?.dispose();
  queue = undefined;
}
