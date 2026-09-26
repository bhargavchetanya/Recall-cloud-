import * as vscode from 'vscode';
import { Answer, errorHeadline, fixNotificationText } from './answer';
import { RecallClient } from './api';
import type { CommandEvent } from './events';
import type { RecallPanel } from './panel';
import type { PanelState } from './panelHtml';

/** The fix offered for the most recent failure, kept for "Recall: Show last fix". */
export interface OfferedFix {
  failedCommand: string;
  errorText: string;
  answer: Answer;
}

/**
 * When a command fails, asks the backend whether the team (or the AI) has a fix and offers it:
 * a notification with Insert / Why? / Dismiss, mirrored in the Recall panel on "Why?".
 */
export class FixAdvisor {
  private last: OfferedFix | undefined;

  constructor(
    private readonly client: RecallClient,
    private readonly panel: RecallPanel,
    private readonly signedIn: () => boolean,
    private readonly notificationsEnabled: () => boolean,
    private readonly log: vscode.LogOutputChannel,
  ) {}

  lastFix(): OfferedFix | undefined {
    return this.last;
  }

  /** Called for every captured event; only failures with output are looked up. Never throws. */
  async onCommandFinished(event: CommandEvent): Promise<Answer | undefined> {
    if (!event.errorText || event.exitCode === null || event.exitCode === 0 || !this.signedIn()) {
      return undefined;
    }
    let answer: Answer;
    try {
      answer = await this.client.resolveError({
        commandText: event.commandText,
        errorText: event.errorText,
        exitCode: event.exitCode,
        cwd: event.cwd,
        repoName: event.repoName,
        shell: event.shell,
        os: event.os,
        osUser: event.osUser,
      });
    } catch (err) {
      this.log.debug(`Could not look up a fix: ${String(err)}`);
      return undefined;
    }
    this.log.info(`Failure "${event.commandText}" → ${answer.source}${answer.command ? `: ${answer.command}` : ''}`);
    const text = fixNotificationText(answer);
    if (!text) {
      return answer;
    }
    this.last = { failedCommand: event.commandText, errorText: event.errorText, answer };
    if (this.notificationsEnabled()) {
      void vscode.window.showInformationMessage(text, 'Insert', 'Why?', 'Dismiss').then((choice) => {
        if (choice === 'Insert') {
          void vscode.commands.executeCommand('recall.insertCommand', answer.command);
        } else if (choice === 'Why?') {
          this.showLast();
        }
      });
    }
    return answer;
  }

  /** Shows the last offered fix in the panel; false when there is none yet. */
  showLast(): boolean {
    if (!this.last) {
      return false;
    }
    this.panel.show(this.state(this.last));
    return true;
  }

  /** "Didn't work": tells the server, so a fix that keeps failing stops being offered. */
  async reportFailed(fixId: string): Promise<void> {
    try {
      await this.client.fixFeedback(fixId, false);
      void vscode.window.showInformationMessage('Recall: thanks, noted that this fix did not work.');
    } catch (err) {
      this.log.warn(`Could not send fix feedback: ${String(err)}`);
    }
  }

  private state(fix: OfferedFix): PanelState {
    return { kind: 'fix', query: `${fix.failedCommand} → ${errorHeadline(fix.errorText)}`, answer: fix.answer };
  }
}
