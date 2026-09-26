import * as path from 'path';
import * as vscode from 'vscode';
import type { Answer, SearchRequest } from './answer';
import { ApiError, RecallClient } from './api';
import { PanelAction, RecallPanel } from './panel';
import type { PanelState } from './panelHtml';
import { RepoResolver } from './repo';

/** "Recall: Search…": ask for a task, send it with the current context, show the one answer. */
export class SearchController implements vscode.Disposable {
  private readonly repos = new RepoResolver();
  private lastQuery = '';

  constructor(
    private readonly client: RecallClient,
    private readonly panel: RecallPanel,
    private readonly signedIn: () => boolean,
    private readonly log: vscode.LogOutputChannel,
  ) {}

  /** With `query` the input box is skipped (used by tests and scripts). Resolves to the answer shown, if any. */
  async run(query?: string): Promise<Answer | undefined> {
    const text = (query ?? (await this.ask()))?.trim();
    if (!text) {
      return undefined;
    }
    this.lastQuery = text;
    if (!this.signedIn()) {
      const choice = await vscode.window.showWarningMessage('Sign in to Recall to search your team\'s history.', 'Sign in');
      if (choice) {
        await vscode.commands.executeCommand('recall.signIn');
      }
      return undefined;
    }
    try {
      const answer = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Window, title: 'Recall: searching…' },
        async () => this.client.search({ query: text, ...(await this.context()) }),
      );
      this.log.info(`Search "${text}" → ${answer.source}${answer.command ? `: ${answer.command}` : ''}`);
      this.panel.show({ kind: 'search', query: text, answer });
      return answer;
    } catch (err) {
      const message =
        err instanceof ApiError && err.status === 401
          ? 'Your Recall session has expired. Run "Recall: Sign in" and search again.'
          : `Recall could not search: ${err instanceof ApiError ? err.detail : String(err)}`;
      this.log.warn(message);
      this.panel.show({ kind: 'search', query: text, answer: null, error: message });
      return undefined;
    }
  }

  /** Button clicks on a search result. */
  async handle(action: PanelAction, state: PanelState): Promise<void> {
    const command = state.answer?.command;
    switch (action) {
      case 'insert':
        if (command) {
          await vscode.commands.executeCommand('recall.insertCommand', command);
        }
        break;
      case 'copy':
        if (command) {
          await vscode.env.clipboard.writeText(command);
          void vscode.window.setStatusBarMessage('Recall: command copied', 2000);
        }
        break;
      case 'reject':
      case 'again':
        await this.run(await this.ask(state.query));
        break;
    }
  }

  private ask(value = this.lastQuery): Thenable<string | undefined> {
    return vscode.window.showInputBox({
      title: 'Recall: Search',
      prompt: 'Describe what you want to do, in your own words',
      placeHolder: 'undo my last commit but keep the changes',
      value,
      ignoreFocusOut: true,
    });
  }

  /** Where the user is working: the active terminal's directory, else the first workspace folder. */
  private async context(): Promise<Omit<SearchRequest, 'query'>> {
    const cwd =
      vscode.window.activeTerminal?.shellIntegration?.cwd?.fsPath ?? vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    const shell = vscode.env.shell ? path.basename(vscode.env.shell).replace(/\.exe$/i, '') : undefined;
    return { cwd, repoName: await this.repos.repoName(cwd), shell, os: process.platform };
  }

  dispose(): void {
    // the panel is owned by the extension
  }
}
