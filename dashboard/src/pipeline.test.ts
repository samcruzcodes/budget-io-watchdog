import { describe, expect, it } from 'vitest';
import type { Stats } from './api';
import { latencyNote, replayNotice, throughputNote } from './pipeline';

function stats(stored: number, skipped: number, samples = stored): Stats {
  return {
    totals: { transactions: 9736, alerts: 494 },
    detectors: [],
    throughput: { windowSeconds: 60, transactions: stored, perSecond: stored / 60 },
    latency: {
      windowSeconds: 300,
      samples,
      p50Ms: samples ? 4.2 : null,
      p95Ms: samples ? 11.6 : null,
      p99Ms: samples ? 30.4 : null,
    },
    duplicates: { windowSeconds: 60, skipped, sinceStartup: skipped },
  };
}

describe('pipeline wording', () => {
  it('says throughput counts new transactions on the real clock', () => {
    expect(throughputNote(stats(12, 0).throughput)).toBe('new transactions stored; real time, last 60 s');
  });

  it('shows the latency percentiles when there are samples', () => {
    expect(latencyNote(stats(12, 0).latency)).toBe('p95 12 ms, p99 30 ms; real time, last 5 min');
  });

  it('explains an empty latency window instead of repeating "n/a"', () => {
    expect(latencyNote(stats(0, 0).latency)).toBe("nothing was stored in the last 5 min, so there's nothing to measure");
  });

  it('says nothing about duplicates when none were skipped', () => {
    expect(replayNotice(stats(12, 0))).toBeNull();
    expect(replayNotice(stats(0, 0))).toBeNull(); // idle or paused: zero throughput needs no warning
  });

  it('explains zero throughput when every arriving message was already stored', () => {
    const notice = replayNotice(stats(0, 268));
    expect(notice).toContain('all 268 messages in the last 60 s');
    expect(notice).toContain("That's why throughput is zero");
  });

  it('stays quiet when a few duplicates arrive alongside new transactions', () => {
    expect(replayNotice(stats(40, 3))).toBeNull();
  });
});
