// How the Overview describes the pipeline's speed, as plain functions so the wording rules are easy to test.
// Throughput and latency only count transactions that were new. A watchdog that's busy skipping re-sent messages
// stores nothing, so those numbers read zero and "n/a"; the notice below says why instead of leaving it a puzzle.

import type { Stats } from './api';
import { count, milliseconds } from './format';

export function throughputNote(throughput: Stats['throughput']): string {
  return `new transactions stored; real time, last ${throughput.windowSeconds} s`;
}

export function latencyNote(latency: Stats['latency']): string {
  const minutes = latency.windowSeconds / 60;
  if (latency.samples === 0) return `nothing was stored in the last ${minutes} min, so there's nothing to measure`;
  return `p95 ${milliseconds(latency.p95Ms)}, p99 ${milliseconds(latency.p99Ms)}; real time, last ${minutes} min`;
}

/**
 * What to say when messages are arriving but none of them is new, or null otherwise. A few skipped messages beside
 * new ones is normal (a restarted simulator re-sends the last moment's transactions), so that case stays quiet here;
 * the Analytics view shows the count either way.
 */
export function replayNotice(stats: Stats): string | null {
  const { skipped, windowSeconds } = stats.duplicates;
  if (skipped === 0 || stats.throughput.transactions > 0) return null;
  const messages = `${count(skipped)} ${skipped === 1 ? 'message' : 'messages'}`;
  return (
    `Messages are arriving, but all ${messages} in the last ${windowSeconds} s were transactions the watchdog had ` +
    `already stored, so it skipped them. That's why throughput is zero: nothing new has arrived. The totals move ` +
    `again when the source reaches transactions it hasn't sent before.`
  );
}
