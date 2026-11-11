import { api, type Stats } from '../api';
import { DetectionQuality } from '../components/DetectionQuality';
import { Metric } from '../components/Metric';
import { ErrorState, Loading, StaleNote } from '../components/States';
import { count, milliseconds, perSecond } from '../format';
import { usePolling } from '../usePolling';
import { ActivityPanel } from './analytics/ActivityPanel';
import { SpendingPanel } from './analytics/SpendingPanel';

const STATS_EVERY_MS = 5000;

export function Analytics() {
  const stats = usePolling(api.stats, STATS_EVERY_MS);

  return (
    <>
      <ActivityPanel />
      <SpendingPanel />
      {stats.loading ? (
        <Loading what="detection quality" />
      ) : !stats.data ? (
        <ErrorState message={stats.error ?? 'No answer from the watchdog.'} onRetry={stats.refresh} />
      ) : (
        <>
          {stats.error && <StaleNote message={stats.error} />}
          <DetectionQuality stats={stats.data} detailed />
          <PipelineSpeed stats={stats.data} />
        </>
      )}
    </>
  );
}

/** How fast the watchdog keeps up. Real time, unlike everything above, which is simulated. */
function PipelineSpeed({ stats }: { stats: Stats }) {
  const { throughput, latency, duplicates } = stats;
  return (
    <section className="panel" aria-labelledby="speed-title">
      <div className="panel-head">
        <h2 id="speed-title">Pipeline speed (real time)</h2>
        <p className="panel-note">
          Latency is from the simulator publishing a transaction to the watchdog storing it, on the real clock, over the
          last {latency.windowSeconds / 60} minutes ({count(latency.samples)} transactions). Throughput counts new
          transactions only; a message whose transaction is already stored is skipped and counted separately. The
          simulated clock runs far faster than this one, so the two don't mix.
        </p>
      </div>
      <div className="metrics">
        <Metric label="Throughput" value={perSecond(throughput.perSecond)} note={`last ${throughput.windowSeconds} s`} />
        <Metric label="Latency, median" value={milliseconds(latency.p50Ms)} note="p50" />
        <Metric label="Latency, 95th percentile" value={milliseconds(latency.p95Ms)} note="p95" />
        <Metric label="Latency, 99th percentile" value={milliseconds(latency.p99Ms)} note="p99" />
        <Metric
          label="Re-sent messages skipped"
          value={count(duplicates.skipped)}
          note={`last ${duplicates.windowSeconds} s; ${count(duplicates.sinceStartup)} since the watchdog started`}
        />
      </div>
    </section>
  );
}
