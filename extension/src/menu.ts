/** One entry of the menu shown when the Recall status bar item is clicked. */
export interface MenuItem {
  label: string;
  command: string;
  description?: string;
}

export interface MenuState {
  signedIn: boolean;
  connected: boolean;
  serverUrl: string;
  capturing: boolean;
  hasLastFix: boolean;
  queued: number;
}

/** The status bar menu: what makes sense right now, most useful first. */
export function menuItems(s: MenuState): MenuItem[] {
  const items: MenuItem[] = [];
  const setServer = { label: '$(server) Set server URL…', command: 'recall.setServerUrl', description: s.serverUrl };
  if (!s.connected) {
    items.push({ ...setServer, description: `${s.serverUrl} is not reachable` });
  }
  if (!s.signedIn) {
    items.push({ label: '$(account) Sign in…', command: 'recall.signIn', description: 'Sign in, join a team or create one' });
  }
  items.push({ label: '$(search) Search…', command: 'recall.search', description: 'Ctrl+Alt+R' });
  if (s.hasLastFix) {
    items.push({ label: '$(lightbulb) Show last fix', command: 'recall.showLastFix' });
  }
  items.push(
    s.capturing
      ? { label: '$(debug-pause) Pause capture', command: 'recall.toggleCapture' }
      : { label: '$(debug-start) Resume capture', command: 'recall.toggleCapture', description: 'Capture is paused' },
  );
  if (s.queued > 0) {
    items.push({
      label: '$(sync) Send queued events now',
      command: 'recall.flush',
      description: `${s.queued} waiting`,
    });
  }
  if (s.connected) {
    items.push(setServer);
  }
  items.push({ label: '$(output) Show log', command: 'recall.showLog' });
  if (s.signedIn) {
    items.push({ label: '$(sign-out) Sign out', command: 'recall.signOut' });
  }
  return items;
}
