import { api, type Stats } from '../api';
import { ErrorState, Loading, StaleNote } from '../components/States';
import { count, milliseconds, percent, perSecond, ruleName } from '../format';
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
      <DetectionTable stats={stats.data} />
    </>
  );
}

function Numbers({ stats }: { stats: Stats }) {
  const { totals, throughput, latency } = stats;
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
          note={`real time, last ${throughput.windowSeconds} s`}
          testId="throughput"
        />
        <Metric
          label="Latency, median"
          value={milliseconds(latency.p50Ms)}
          note={`p95 ${milliseconds(latency.p95Ms)}, p99 ${milliseconds(latency.p99Ms)}; real time, last ${latency.windowSeconds / 60} min`}
          testId="latency"
        />
      </div>
    </section>
  );
}

function Metric({ label, value, note, testId }: { label: string; value: string; note?: string; testId: string }) {
  return (
    <div className="metric">
      <p className="metric-label">{label}</p>
      <p className="metric-value" data-testid={testId}>
        {value}
      </p>
      {note && <p className="metric-note">{note}</p>}
    </div>
  );
}

function DetectionTable({ stats }: { stats: Stats }) {
  return (
    <section className="panel" aria-labelledby="detection-title">
      <div className="panel-head">
        <h2 id="detection-title">Detection quality</h2>
        <p className="panel-note">
          Scored against the simulator's answer key. Demo rates: problems are planted far more often than in real life,
          so these aren't real-world numbers.
        </p>
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th scope="col">Rule</th>
              <th scope="col" className="number">Alerts</th>
              <th scope="col" className="number">Precision</th>
              <th scope="col" className="number">Recall</th>
            </tr>
          </thead>
          <tbody>
            {stats.detectors.map((rule) => (
              <tr key={rule.detector}>
                <th scope="row">{ruleName(rule.detector)}</th>
                <td className="number">{count(rule.alerts)}</td>
                <td className="number" title={rule.precision === null ? 'No labeled alerts yet' : undefined}>
                  {percent(rule.precision)}
                </td>
                <td className="number" title={rule.recall === null ? 'No planted scenarios yet' : undefined}>
                  {percent(rule.recall)}
                  {rule.scenarios > 0 && (
                    <span className="cell-note">
                      {' '}
                      {rule.caughtScenarios} of {rule.scenarios}
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
