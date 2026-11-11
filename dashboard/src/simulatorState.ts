// What the header says about the simulator, as plain functions so the rules are easy to test.

import type { SimulatorStatus } from './api';

export type SimulatorTone = 'running' | 'paused' | 'unreachable';

export interface SimulatorSummary {
  tone: SimulatorTone;
  text: string;
  hint: string | null; // a second, quieter line
}

/**
 * @param stale the last status request failed, so `status` is the previous answer and may no longer be true
 */
export function summarize(status: SimulatorStatus, stale: boolean): SimulatorSummary {
  if (stale) {
    return { tone: 'unreachable', text: "Simulator isn't answering", hint: 'Showing its last known state; retrying.' };
  }
  if (status.state === 'RUNNING') {
    return { tone: 'running', text: 'Simulator running', hint: null };
  }
  if (status.pausedItself) {
    const days = status.pauseAfterDays;
    return {
      tone: 'paused',
      text: `Simulator paused itself after ${days} simulated days`,
      hint: `Resume to run another ${days}.`,
    };
  }
  return { tone: 'paused', text: 'Simulator paused', hint: null };
}

/** "Start" only for a run that's paused and has never published anything; otherwise "Resume" or "Pause". */
export function toggleLabel(status: SimulatorStatus): 'Start' | 'Resume' | 'Pause' {
  if (status.state === 'RUNNING') return 'Pause';
  return status.published === 0 && status.resumedFrom === null ? 'Start' : 'Resume';
}
