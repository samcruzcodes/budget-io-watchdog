import type { Stats } from '../api';
import { count, percent, ruleName } from '../format';

/**
 * Precision and recall per rule. `detailed` adds the counts behind them and thin bars, for the Analytics view.
 * Labeled as demo rates: the demo plants problems far more often than real life.
 */
export function DetectionQuality({ stats, detailed = false }: { stats: Stats; detailed?: boolean }) {
  // An alert whose answer-key entry hasn't arrived can't be scored yet. Usually none; shown only when there are some,
  // so the true and false counts still add up to the alerts.
  const anyUnscored = detailed && stats.detectors.some((rule) => rule.unlabeledAlerts > 0);
  return (
    <section className="panel" aria-labelledby="detection-title">
      <div className="panel-head">
        <h2 id="detection-title">Detection quality</h2>
        <p className="panel-note">
          <strong>Precision:</strong> of the alerts a rule raised, the share that were real planted problems (high
          means few false alarms). <strong>Recall:</strong> of the problems the simulator planted, the share the rule
          caught (high means few misses). Scored against the simulator's answer key, which the rules never see. Demo
          rates: problems are planted far more often than in real life, so these aren't real-world numbers. "n/a"
          means there's nothing to score yet.
        </p>
      </div>
      <div className="table-scroll">
        <table className={detailed ? 'stack-on-mobile' : 'quality-compact'}>
          <thead>
            <tr>
              <th scope="col">Rule</th>
              <th scope="col" className="number">Alerts</th>
              {detailed && <th scope="col" className="number">True</th>}
              {detailed && <th scope="col" className="number">False</th>}
              {anyUnscored && <th scope="col" className="number">Not scored yet</th>}
              <th scope="col" className="number">Precision</th>
              <th scope="col" className="number">Recall</th>
              {detailed && <th scope="col" className="number">Missed</th>}
            </tr>
          </thead>
          <tbody>
            {stats.detectors.map((rule) => (
              <tr key={rule.detector}>
                <th scope="row">{ruleName(rule.detector)}</th>
                <td className="number" data-label="Alerts">{count(rule.alerts)}</td>
                {detailed && <td className="number" data-label="True">{count(rule.truePositives)}</td>}
                {detailed && <td className="number" data-label="False">{count(rule.falsePositives)}</td>}
                {anyUnscored && <td className="number" data-label="Not scored yet">{count(rule.unlabeledAlerts)}</td>}
                <td
                  className="number"
                  data-label="Precision"
                  title={rule.precision === null ? 'No labeled alerts yet' : undefined}
                >
                  <span>
                    {detailed && <ShareBar share={rule.precision} />}
                    {percent(rule.precision)}
                  </span>
                </td>
                <td
                  className="number"
                  data-label="Recall"
                  title={rule.recall === null ? 'No planted scenarios yet' : undefined}
                >
                  <span>
                    {detailed && <ShareBar share={rule.recall} />}
                    {percent(rule.recall)}
                    {rule.scenarios > 0 && (
                      <span className="cell-note">
                        {' '}
                        {rule.caughtScenarios} of {rule.scenarios}
                      </span>
                    )}
                  </span>
                </td>
                {detailed && <td className="number" data-label="Missed">{count(rule.falseNegatives)}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {detailed && (
        <p className="panel-note">
          "True" and "False" count alerts: those that landed on a planted problem of the rule's type, and those that
          didn't. Recall counts incidents, not alerts: one incident can be several transactions (a card-testing run),
          and it's caught if the rule flagged at least one of them. "Missed" is planted incidents with no alert.
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
