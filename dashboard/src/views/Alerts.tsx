import { useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { RuleBadge } from '../components/RuleBadge';
import { Empty, ErrorState, Loading, StaleNote } from '../components/States';
import { addAlerts, emptyFeed } from '../feed';
import { ruleName, simulatedDateTime } from '../format';
import { useHashParams } from '../useHashView';
import { useLiveStream } from '../LiveStream';
import { usePolling } from '../usePolling';

const ALERTS_LIMIT = 100;
// New alerts arrive over the live stream; this slower reload catches any the stream missed.
const ALERTS_EVERY_MS = 15_000;

/** Recent alerts, optionally for one rule, with the rule's own explanation. New ones arrive from the live stream. */
export function Alerts() {
  // A link can open this view already narrowed down: "#alerts?user=demo-user&rule=price-hike&alert=<id>" is how the
  // Scenarios tab points at the alert a run produced.
  const params = useHashParams();
  const user = params.get('user') ?? ''; // '' = everyone
  const highlighted = params.get('alert');
  const [rule, setRule] = useState(params.get('rule') ?? ''); // '' = every rule
  const stats = usePolling(api.stats, null); // only for the list of rule IDs
  const listed = usePolling(() => api.alerts(ALERTS_LIMIT, rule || undefined, user || undefined), ALERTS_EVERY_MS, [rule, user]);
  const { feed } = useLiveStream();

  // What the API returned, plus anything the stream brought since, for the same rule; each alert once.
  const alerts = useMemo(() => {
    if (!listed.data) return [];
    const live = feed.alerts.filter(
      (alert) => (!rule || alert.detector === rule) && (!user || alert.userId === user),
    );
    return addAlerts({ ...emptyFeed, alerts: listed.data }, live).alerts;
  }, [listed.data, feed.alerts, rule, user]);

  // Bring the alert a link pointed at into view, once it's on the page.
  const highlightedShown = highlighted !== null && alerts.some((alert) => alert.alertId === highlighted);
  useEffect(() => {
    if (highlightedShown) document.getElementById(`alert-${highlighted}`)?.scrollIntoView({ block: 'center' });
  }, [highlightedShown, highlighted]);

  return (
    <section className="panel" aria-labelledby="alerts-title">
      <div className="panel-head">
        <h2 id="alerts-title">Alerts</h2>
        <p className="panel-note">
          What the six rules flagged, newest first, each with the rule's own explanation. Times are simulated (UTC).
        </p>
      </div>
      <div className="toolbar">
        <div className="field">
          <label htmlFor="rule-filter">Rule</label>
          <select id="rule-filter" value={rule} onChange={(event) => setRule(event.target.value)}>
            <option value="">All rules</option>
            {stats.data?.detectors.map((d) => (
              <option key={d.detector} value={d.detector}>
                {ruleName(d.detector)}
              </option>
            ))}
          </select>
        </div>
        {user && (
          <p className="toolbar-note" data-testid="alerts-user-filter">
            Only <strong>{user}</strong>'s alerts. <a href="#alerts">Show everyone's</a>
          </p>
        )}
      </div>
      {listed.data && listed.error && <StaleNote message={listed.error} />}
      {listed.loading ? (
        <Loading what="alerts" />
      ) : !listed.data ? (
        <ErrorState message={listed.error ?? 'No answer from the watchdog.'} onRetry={listed.refresh} />
      ) : alerts.length === 0 ? (
        <Empty title={rule ? `No ${ruleName(rule).toLowerCase()} alerts yet` : 'No alerts yet'}>
          The simulator plants problems after its first 30 simulated days, about a minute into a fresh run.
        </Empty>
      ) : (
        <div className="table-scroll">
          <table data-testid="alerts-table" className="stack-on-mobile">
            <thead>
              <tr>
                <th scope="col">Time (simulated)</th>
                <th scope="col">Rule</th>
                <th scope="col">Card</th>
                <th scope="col">Why it was flagged</th>
              </tr>
            </thead>
            <tbody>
              {alerts.map((alert) => (
                <tr
                  key={alert.alertId}
                  id={`alert-${alert.alertId}`}
                  data-rule={alert.detector}
                  className={alert.alertId === highlighted ? 'row-highlight' : undefined}
                >
                  <td className="nowrap" data-label="Time (simulated)">{simulatedDateTime(alert.occurredAt)}</td>
                  <td data-label="Rule">
                    <RuleBadge detector={alert.detector} />
                  </td>
                  <td className="nowrap" data-label="Card">{alert.cardId}</td>
                  <td data-label="Why">{alert.explanation}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
