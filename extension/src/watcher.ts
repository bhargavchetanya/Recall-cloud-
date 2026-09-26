import { randomUUID } from 'crypto';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { MAX_ERROR_CHARS, FinishedExecution, keepTail } from './events';
import { RepoResolver } from './repo';

interface Session {
  id: string;
  seq: number;
}

interface Running {
  startedAt: Date;
  output: string;
  done: Promise<void>;
}

const OUTPUT_READ_GRACE_MS = 2000;
const SHELL_INTEGRATION_HINT_DELAY_MS = 10_000;

/**
 * Watches every terminal through VS Code's shell integration and reports each finished command.
 * Output is read from the start of each execution and only the last 8 KB is kept in memory.
 */
export class TerminalWatcher implements vscode.Disposable {
  private readonly sessions = new WeakMap<vscode.Terminal, Session>();
  private readonly running = new Map<vscode.TerminalShellExecution, Running>();
  private readonly disposables: vscode.Disposable[] = [];
  private readonly repos = new RepoResolver();
  private hintShown = false;

  constructor(
    private readonly onFinished: (exec: FinishedExecution) => void,
    private readonly captureOutput: () => boolean,
    private readonly log: vscode.LogOutputChannel,
  ) {
    this.disposables.push(
      vscode.window.onDidStartTerminalShellExecution((e) => this.started(e)),
      vscode.window.onDidEndTerminalShellExecution((e) => void this.ended(e)),
      vscode.window.onDidOpenTerminal((t) => this.checkShellIntegrationLater(t)),
    );
    vscode.window.terminals.forEach((t) => this.checkShellIntegrationLater(t));
  }

  private started(e: vscode.TerminalShellExecutionStartEvent): void {
    const run: Running = { startedAt: new Date(), output: '', done: Promise.resolve() };
    if (this.captureOutput()) {
      // read() only yields data emitted after it is first called, so it must start right here.
      run.done = (async () => {
        try {
          for await (const chunk of e.execution.read()) {
            run.output += chunk;
            if (run.output.length > 2 * MAX_ERROR_CHARS) {
              run.output = keepTail(run.output, MAX_ERROR_CHARS);
            }
          }
        } catch (err) {
          this.log.debug(`Stopped reading terminal output: ${String(err)}`);
        }
      })();
    }
    this.running.set(e.execution, run);
  }

  private async ended(e: vscode.TerminalShellExecutionEndEvent): Promise<void> {
    const run = this.running.get(e.execution);
    this.running.delete(e.execution);
    const commandLine = e.execution.commandLine;
    if (!commandLine.value || commandLine.confidence === vscode.TerminalShellExecutionCommandLineConfidence.Low) {
      return;
    }
    if (run) {
      await Promise.race([run.done, delay(OUTPUT_READ_GRACE_MS)]);
    }
    const session = this.session(e.terminal);
    const cwd = e.execution.cwd?.fsPath;
    this.onFinished({
      sessionId: session.id,
      seq: ++session.seq,
      commandLine: commandLine.value,
      cwd,
      repoName: await this.repos.repoName(cwd),
      shell: shellName(e.terminal),
      os: process.platform,
      exitCode: e.exitCode,
      startedAt: run?.startedAt ?? new Date(),
      endedAt: new Date(),
      output: run ? keepTail(run.output, MAX_ERROR_CHARS) : undefined,
      osUser: currentUser(),
    });
  }

  private session(terminal: vscode.Terminal): Session {
    let s = this.sessions.get(terminal);
    if (!s) {
      s = { id: randomUUID(), seq: 0 };
      this.sessions.set(terminal, s);
    }
    return s;
  }

  private checkShellIntegrationLater(terminal: vscode.Terminal): void {
    const timer = setTimeout(() => {
      if (this.hintShown || terminal.shellIntegration || terminal.exitStatus) {
        return;
      }
      this.hintShown = true;
      this.log.warn(`Shell integration is not active in terminal "${terminal.name}"; its commands are not captured.`);
      void vscode.window
        .showInformationMessage(
          `Recall can't see commands in "${terminal.name}" because VS Code shell integration isn't active there.`,
          'How to enable it',
        )
        .then((choice) => {
          if (choice) {
            void vscode.env.openExternal(
              vscode.Uri.parse('https://code.visualstudio.com/docs/terminal/shell-integration'),
            );
          }
        });
    }, SHELL_INTEGRATION_HINT_DELAY_MS);
    this.disposables.push({ dispose: () => clearTimeout(timer) });
  }

  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
    this.running.clear();
  }
}

function shellName(terminal: vscode.Terminal): string | undefined {
  const options = terminal.creationOptions as vscode.TerminalOptions;
  const shellPath = options.shellPath ?? vscode.env.shell;
  return shellPath ? path.basename(shellPath).replace(/\.exe$/i, '') : undefined;
}

function currentUser(): string | undefined {
  try {
    return os.userInfo().username;
  } catch {
    return undefined;
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
