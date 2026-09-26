import * as vscode from 'vscode';
import { ApiError, AuthResponse, RecallClient } from './api';

const TOKEN_KEY = 'recall.token';

/** The JWT lives only in SecretStorage (OS keychain), never in settings or globalState. */
export class TokenStore implements vscode.Disposable {
  private cached: string | undefined;
  private loaded = false;
  private readonly changed = new vscode.EventEmitter<boolean>();
  readonly onDidChange = this.changed.event;
  private readonly subscription: vscode.Disposable;

  constructor(private readonly secrets: vscode.SecretStorage) {
    this.subscription = secrets.onDidChange(async (e) => {
      if (e.key === TOKEN_KEY) {
        this.cached = await secrets.get(TOKEN_KEY);
        this.changed.fire(!!this.cached);
      }
    });
  }

  async get(): Promise<string | undefined> {
    if (!this.loaded) {
      this.cached = await this.secrets.get(TOKEN_KEY);
      this.loaded = true;
    }
    return this.cached;
  }

  async set(token: string): Promise<void> {
    this.cached = token;
    this.loaded = true;
    await this.secrets.store(TOKEN_KEY, token);
    this.changed.fire(true);
  }

  async clear(): Promise<void> {
    this.cached = undefined;
    this.loaded = true;
    await this.secrets.delete(TOKEN_KEY);
    this.changed.fire(false);
  }

  dispose(): void {
    this.subscription.dispose();
    this.changed.dispose();
  }
}

/** Passing these to the `recall.signIn` command skips the prompts (used by tests and scripts). */
export interface SignInArgs {
  mode: 'login' | 'join' | 'register';
  email: string;
  password: string;
  displayName?: string;
  inviteCode?: string;
  teamName?: string;
}

export async function signIn(client: RecallClient, tokens: TokenStore, args?: SignInArgs): Promise<boolean> {
  const request = args ?? (await promptForSignIn());
  if (!request) {
    return false;
  }
  let response: AuthResponse;
  try {
    response = await callServer(client, request);
  } catch (err) {
    const detail = err instanceof ApiError ? err.detail : `Cannot reach the Recall server (${String(err)}).`;
    void vscode.window.showErrorMessage(`Recall sign-in failed: ${detail}`);
    return false;
  }
  await tokens.set(response.token);
  if (response.inviteCode) {
    const copy = 'Copy invite code';
    void vscode.window
      .showInformationMessage(`Recall team created. Invite code: ${response.inviteCode}`, copy)
      .then((choice) => {
        if (choice === copy) {
          void vscode.env.clipboard.writeText(response.inviteCode!);
        }
      });
  } else if (!args) {
    void vscode.window.showInformationMessage('Recall: signed in.');
  }
  return true;
}

function callServer(client: RecallClient, r: SignInArgs): Promise<AuthResponse> {
  switch (r.mode) {
    case 'login':
      return client.login({ email: r.email, password: r.password });
    case 'join':
      return client.join({
        inviteCode: r.inviteCode ?? '',
        email: r.email,
        password: r.password,
        displayName: r.displayName ?? '',
      });
    case 'register':
      return client.register({
        teamName: r.teamName ?? '',
        email: r.email,
        password: r.password,
        displayName: r.displayName ?? '',
      });
  }
}

async function promptForSignIn(): Promise<SignInArgs | undefined> {
  const picked = await vscode.window.showQuickPick(
    [
      { label: 'Sign in', description: 'I already have an account', mode: 'login' as const },
      { label: 'Join a team', description: 'I have an invite code', mode: 'join' as const },
      { label: 'Create a team', description: 'Start a new team and get an invite code', mode: 'register' as const },
    ],
    { title: 'Recall', placeHolder: 'How do you want to sign in?' },
  );
  if (!picked) {
    return undefined;
  }
  const inviteCode = picked.mode === 'join' ? await ask('Invite code', false, required) : undefined;
  if (picked.mode === 'join' && !inviteCode) {
    return undefined;
  }
  const teamName = picked.mode === 'register' ? await ask('Team name', false, required) : undefined;
  if (picked.mode === 'register' && !teamName) {
    return undefined;
  }
  const email = await ask('Email', false, (v) => (/^\S+@\S+\.\S+$/.test(v) ? undefined : 'Enter an email address'));
  if (!email) {
    return undefined;
  }
  const displayName = picked.mode === 'login' ? undefined : await ask('Your name, as teammates will see it', false, required);
  if (picked.mode !== 'login' && !displayName) {
    return undefined;
  }
  const password = await ask('Password', true, (v) =>
    picked.mode === 'login' || v.length >= 8 ? undefined : 'At least 8 characters',
  );
  if (!password) {
    return undefined;
  }
  return { mode: picked.mode, email, password, displayName, inviteCode, teamName };
}

function required(value: string): string | undefined {
  return value.trim() ? undefined : 'Required';
}

function ask(prompt: string, password: boolean, validateInput: (v: string) => string | undefined) {
  return vscode.window.showInputBox({ title: 'Recall', prompt, password, ignoreFocusOut: true, validateInput });
}
