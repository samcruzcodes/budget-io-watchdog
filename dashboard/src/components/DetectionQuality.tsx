import type { Stats } from '../api';
import { count, percent, ruleName } from '../format';

/**
 * Precision and recall per rule. `detailed` adds the counts behind them and thin bars, for the Analytics view.
 * Labeled as demo rates: the demo plants problems far more often than real life.
 */
export function DetectionQuality({ stats, detailed = false }: { stats: Stats; detailed?: boolean }) {
  return (
    <section className="panel" aria-labelledby="detection-title">
      <div className="panel-head">
        <h2 id="detection-title">Detection quality</h2>
        <p className="panel-note">
          Scored against the simulator's answer key. Demo rates: problems are planted far more often than in real life,
          so these aren't real-world numbers. "n/a" means there's nothing to score yet.
        </p>
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th scope="col">Rule</th>
              <th scope="col" className="number">Alerts</th>
              {detailed && <th scope="col" className="number">True</th>}
              {detailed && <th scope="col" className="number">False</th>}
              <th scope="col" className="number">Precision</th>
              <th scope="col" className="number">Recall</th>
              {detailed && <th scope="col" className="number">Missed</th>}
            </tr>
          </thead>
          <tbody>
            {stats.detectors.map((rule) => (
              <tr key={rule.detector}>
                <th scope="row">{ruleName(rule.detector)}</th>
                <td className="number">{count(rule.alerts)}</td>
                {detailed && <td className="number">{count(rule.truePositives)}</td>}
                {detailed && <td className="number">{count(rule.falsePositives)}</td>}
                <td className="number" title={rule.precision === null ? 'No labeled alerts yet' : undefined}>
                  {detailed && <ShareBar share={rule.precision} />}
                  {percent(rule.precision)}
                </td>
                <td className="number" title={rule.recall === null ? 'No planted scenarios yet' : undefined}>
                  {detailed && <ShareBar share={rule.recall} />}
                  {percent(rule.recall)}
                  {rule.scenarios > 0 && (
                    <span className="cell-note">
                      {' '}
                      {rule.caughtScenarios} of {rule.scenarios}
                    </span>
                  )}
                </td>
                {detailed && <td className="number">{count(rule.falseNegatives)}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {detailed && (
        <p className="panel-note">
          Precision counts alerts: the share that landed on a planted problem of the rule's type. Recall counts
          incidents: the share of planted ones the rule caught at least once. "Missed" is planted incidents with no alert.
        </p>
      )}
    </section>
  );
}

/** A thin bar for a share from 0 to 1, beside the number (decoration; the number carries the meaning). */
function ShareBar({ share }: { share: number | null }) {
  return (
    <span className="share-track" aria-hidden="true">
      <span className="share-fill" style={{ width: `${(share ?? 0) * 100}%` }} />
    </span>
  );
}
