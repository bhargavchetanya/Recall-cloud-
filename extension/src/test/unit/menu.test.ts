import { describe, expect, it } from 'vitest';
import { menuItems } from '../../menu';

const commands = (s: Parameters<typeof menuItems>[0]) => menuItems(s).map((i) => i.command);
const up = { connected: true, serverUrl: 'https://3-91-20-7.sslip.io' };

describe('menuItems', () => {
  it('offers sign-in first when signed out, and no sign-out', () => {
    expect(commands({ signedIn: false, capturing: true, hasLastFix: false, queued: 0, ...up })).toEqual([
      'recall.signIn',
      'recall.search',
      'recall.toggleCapture',
      'recall.setServerUrl',
      'recall.showLog',
    ]);
  });

  it('shows the last fix, queued events and sign-out when they apply', () => {
    expect(commands({ signedIn: true, capturing: true, hasLastFix: true, queued: 3, ...up })).toEqual([
      'recall.search',
      'recall.showLastFix',
      'recall.toggleCapture',
      'recall.flush',
      'recall.setServerUrl',
      'recall.showLog',
      'recall.signOut',
    ]);
  });

  it('labels the capture toggle by what it will do', () => {
    const paused = menuItems({ signedIn: true, capturing: false, hasLastFix: false, queued: 0, ...up });
    expect(paused.find((i) => i.command === 'recall.toggleCapture')?.label).toContain('Resume capture');
    const running = menuItems({ signedIn: true, capturing: true, hasLastFix: false, queued: 0, ...up });
    expect(running.find((i) => i.command === 'recall.toggleCapture')?.label).toContain('Pause capture');
  });

  it('shows how many events are waiting', () => {
    const item = menuItems({ signedIn: true, capturing: true, hasLastFix: false, queued: 12, ...up }).find(
      (i) => i.command === 'recall.flush',
    );
    expect(item?.description).toBe('12 waiting');
  });

  it('puts "Set server URL" first, with the reason, when the server is unreachable', () => {
    const items = menuItems({ signedIn: false, capturing: true, hasLastFix: false, queued: 0, connected: false,
      serverUrl: 'http://localhost:8080' });
    expect(items[0].command).toBe('recall.setServerUrl');
    expect(items[0].description).toBe('http://localhost:8080 is not reachable');
    expect(items.filter((i) => i.command === 'recall.setServerUrl')).toHaveLength(1);
  });
});
