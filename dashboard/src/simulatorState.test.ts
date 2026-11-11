import { describe, expect, it } from 'vitest';
import type { SimulatorStatus } from './api';
import { summarize, toggleLabel } from './simulatorState';

const running: SimulatorStatus = {
  state: 'RUNNING',
  daysPerMinute: 30,
  simulatedNow: '2026-03-01T00:00:00Z',
  published: 500,
  speeds: [1, 7, 30, 90, 365],
  resumedFrom: null,
  pauseAfterDays: 365,
  pausedItself: false,
};

describe('simulator state in the header', () => {
  it('says running or paused', () => {
    expect(summarize(running, false)).toEqual({ tone: 'running', text: 'Simulator running', hint: null });
    expect(summarize({ ...running, state: 'PAUSED' }, false).text).toBe('Simulator paused');
  });

  it('says why when the simulator paused itself, and what Resume does', () => {
    const summary = summarize({ ...running, state: 'PAUSED', pausedItself: true }, false);
    expect(summary.text).toBe('Simulator paused itself after 365 simulated days');
    expect(summary.hint).toBe('Resume to run another 365.');
  });

  it("doesn't claim a state it can't confirm", () => {
    const summary = summarize(running, true);
    expect(summary.tone).toBe('unreachable');
    expect(summary.text).toBe("Simulator isn't answering");
  });

  it('offers Start only for a paused run that has published nothing', () => {
    expect(toggleLabel(running)).toBe('Pause');
    expect(toggleLabel({ ...running, state: 'PAUSED' })).toBe('Resume');
    expect(toggleLabel({ ...running, state: 'PAUSED', published: 0 })).toBe('Start');
    // Restarted and continuing an earlier run: nothing published by this process yet, but it isn't a first start.
    expect(toggleLabel({ ...running, state: 'PAUSED', published: 0, resumedFrom: '2027-01-01T00:00:00Z' })).toBe('Resume');
  });
});
