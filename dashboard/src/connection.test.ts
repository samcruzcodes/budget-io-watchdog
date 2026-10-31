import { describe, expect, it } from 'vitest';
import { FAILURES_BEFORE_DISCONNECTED, connected, describe as label, failed, initialConnection, retryDelayMs } from './connection';

describe('the stream connection', () => {
  it('starts connecting and goes live on open', () => {
    expect(initialConnection.status).toBe('connecting');
    expect(connected()).toEqual({ status: 'live', failures: 0 });
  });

  it('says reconnecting after a failure, and disconnected after several in a row', () => {
    let state = connected();
    for (let i = 1; i < FAILURES_BEFORE_DISCONNECTED; i++) {
      state = failed(state);
      expect(state.status).toBe('reconnecting');
    }
    state = failed(state);
    expect(state.status).toBe('disconnected');
  });

  it('forgets earlier failures once it connects again', () => {
    const down = failed(failed(failed(connected())));

    expect(down.status).toBe('disconnected');
    expect(failed(connected()).status).toBe('reconnecting');
  });

  it('backs off 1, 2, 4, then 8 seconds at most', () => {
    expect([1, 2, 3, 4, 5, 10].map(retryDelayMs)).toEqual([1000, 2000, 4000, 8000, 8000, 8000]);
  });

  it('labels every state in words', () => {
    expect(label('disconnected')).toBe('Disconnected, retrying');
    expect(label('live')).toBe('Live');
  });
});
