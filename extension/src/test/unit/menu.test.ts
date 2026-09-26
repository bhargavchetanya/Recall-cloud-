import { describe, expect, it } from 'vitest';
import { menuItems } from '../../menu';

const commands = (s: Parameters<typeof menuItems>[0]) => menuItems(s).map((i) => i.command);

describe('menuItems', () => {
  it('offers sign-in first when signed out, and no sign-out', () => {
    expect(commands({ signedIn: false, capturing: true, hasLastFix: false, queued: 0 })).toEqual([
      'recall.signIn',
      'recall.search',
      'recall.toggleCapture',
      'recall.showLog',
    ]);
  });

  it('shows the last fix, queued events and sign-out when they apply', () => {
    expect(commands({ signedIn: true, capturing: true, hasLastFix: true, queued: 3 })).toEqual([
      'recall.search',
      'recall.showLastFix',
      'recall.toggleCapture',
      'recall.flush',
      'recall.showLog',
      'recall.signOut',
    ]);
  });

  it('labels the capture toggle by what it will do', () => {
    const paused = menuItems({ signedIn: true, capturing: false, hasLastFix: false, queued: 0 });
    expect(paused.find((i) => i.command === 'recall.toggleCapture')?.label).toContain('Resume capture');
    const running = menuItems({ signedIn: true, capturing: true, hasLastFix: false, queued: 0 });
    expect(running.find((i) => i.command === 'recall.toggleCapture')?.label).toContain('Pause capture');
  });

  it('shows how many events are waiting', () => {
    const item = menuItems({ signedIn: true, capturing: true, hasLastFix: false, queued: 12 }).find(
      (i) => i.command === 'recall.flush',
    );
    expect(item?.description).toBe('12 waiting');
  });
});
