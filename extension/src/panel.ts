import { randomBytes } from 'crypto';
import * as vscode from 'vscode';
import { PanelState, renderPanelHtml } from './panelHtml';

export type PanelAction = 'insert' | 'copy' | 'reject' | 'again';

/** The single Recall result panel. Re-used for every search so results do not pile up as tabs. */
export class RecallPanel implements vscode.Disposable {
  private panel: vscode.WebviewPanel | undefined;
  private state: PanelState | undefined;
  private html = '';

  constructor(private readonly onAction: (action: PanelAction, state: PanelState) => void) {}

  show(state: PanelState): void {
    this.state = state;
    if (!this.panel) {
      this.panel = vscode.window.createWebviewPanel('recall.result', 'Recall', vscode.ViewColumn.Beside, {
        enableScripts: true,
        localResourceRoots: [],
      });
      this.panel.onDidDispose(() => (this.panel = undefined));
      this.panel.webview.onDidReceiveMessage((msg: { action?: PanelAction }) => {
        if (msg.action && this.state) {
          this.onAction(msg.action, this.state);
        }
      });
    }
    const nonce = randomBytes(16).toString('base64');
    this.html = renderPanelHtml(state, nonce, this.panel.webview.cspSource, new Date());
    this.panel.webview.html = this.html;
    this.panel.reveal(vscode.ViewColumn.Beside, true);
  }

  /** What is currently rendered; used by the integration tests. */
  currentHtml(): string {
    return this.html;
  }

  dispose(): void {
    this.panel?.dispose();
  }
}

/** Puts the command on the prompt of the active terminal (or a new one) without running it. */
export function insertIntoTerminal(command: string): vscode.Terminal {
  const terminal = vscode.window.activeTerminal ?? vscode.window.createTerminal({ name: 'Recall' });
  terminal.show();
  terminal.sendText(command, false);
  return terminal;
}
