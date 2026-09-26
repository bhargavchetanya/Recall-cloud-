import { describe, expect, it } from 'vitest';
import { StatusState, renderStatus } from '../../status';

const base: StatusState = { connected: true, queued: 0, signedIn: true, capturing: true };
const HINT = 'Click for Recall actions';

describe('renderStatus', () => {
  it('shows a filled dot when connected', () => {
    expect(renderStatus(base)).toEqual({ text: 'Recall ●', tooltip: `Recall: connected\n${HINT}` });
  });

  it('shows a hollow dot when offline with nothing queued', () => {
    expect(renderStatus({ ...base, connected: false })).toEqual({
      text: 'Recall ○',
      tooltip: `Recall: offline\n${HINT}`,
    });
  });

  it('shows the queued count when offline', () => {
    expect(renderStatus({ ...base, connected: false, queued: 12 })).toEqual({
      text: 'Recall ○ (12 queued)',
      tooltip: `Recall: offline\n12 events waiting to send\n${HINT}`,
    });
  });

  it('uses the singular for one queued event', () => {
    expect(renderStatus({ ...base, connected: false, queued: 1 }).tooltip).toContain('1 event waiting to send');
  });

  it('tells the user when they are signed out or capture is paused', () => {
    const view = renderStatus({ ...base, signedIn: false, capturing: false });
    expect(view.text).toBe('Recall ●');
    expect(view.tooltip).toBe(`Recall: connected\nNot signed in\nCapture is paused\n${HINT}`);
  });
});
