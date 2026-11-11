import { api, type Stats } from '../api';
import { ErrorState, Loading, StaleNote } from '../components/States';
import { DetectionQuality } from '../components/DetectionQuality';
import { Metric } from '../components/Metric';
import { count, milliseconds, perSecond } from '../format';
import { latencyNote, replayNotice, throughputNote } from '../pipeline';
import { usePolling } from '../usePolling';

const STATS_EVERY_MS = 5000;

export function Overview() {
  const stats = usePolling(api.stats, STATS_EVERY_MS);

  if (stats.loading) return <Loading what="the overview" />;
  if (!stats.data) return <ErrorState message={stats.error ?? 'No answer from the watchdog.'} onRetry={stats.refresh} />;
  return (
    <>
      {stats.error && <StaleNote message={stats.error} />}
      <Numbers stats={stats.data} />
      <DetectionQuality stats={stats.data} />
    </>
  );
}

function Numbers({ stats }: { stats: Stats }) {
  const { totals, throughput, latency } = stats;
  const replay = replayNotice(stats);
  return (
    <section aria-labelledby="numbers-title">
      <h2 id="numbers-title" className="visually-hidden">
        Totals and speed
      </h2>
      <div className="metrics">
        <Metric label="Transactions processed" value={count(totals.transactions)} testId="total-transactions" />
        <Metric label="Alerts raised" value={count(totals.alerts)} testId="total-alerts" />
        <Metric
          label="Throughput"
          value={perSecond(throughput.perSecond)}
          note={throughputNote(throughput)}
          testId="throughput"
        />
        <Metric
          label="Latency, median"
          value={milliseconds(latency.p50Ms)}
          note={latencyNote(latency)}
          testId="latency"
        />
      </div>
      {replay && (
        <p className="stale-note notice-below" role="status" data-testid="replay-notice">
          {replay}
        </p>
      )}
    </section>
  );
}
