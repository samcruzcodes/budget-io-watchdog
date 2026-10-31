import { useState } from 'react';
import { api, type Activity } from '../../api';
import { ColumnChart, type Column } from '../../charts/ColumnChart';
import { Empty, ErrorState, Loading } from '../../components/States';
import { count, money, simulatedDate } from '../../format';
import { usePolling } from '../../usePolling';

const RANGES = [30, 90, 365];
const ACTIVITY_EVERY_MS = 10_000;

/**
 * Transactions, spending, and alerts per simulated day: three charts stacked on the same time axis (small multiples),
 * never one chart with two y-axes.
 */
export function ActivityPanel() {
  const [days, setDays] = useState(30);
  const activity = usePolling(() => api.activity(days), ACTIVITY_EVERY_MS, [days]);

  return (
    <section className="panel" aria-labelledby="activity-title">
      <div className="panel-head">
        <h2 id="activity-title">Activity per simulated day</h2>
        <p className="panel-note">
          Days are simulated (UTC), counted back from the newest transaction. Spending leaves out income.
        </p>
      </div>
      <div className="toolbar">
        <div className="field">
          <label htmlFor="activity-days">Range</label>
          <select id="activity-days" value={days} onChange={(event) => setDays(Number(event.target.value))}>
            {RANGES.map((d) => (
              <option key={d} value={d}>
                Last {d} simulated days
              </option>
            ))}
          </select>
        </div>
      </div>
      {activity.loading ? (
        <Loading what="activity" />
      ) : !activity.data ? (
        <ErrorState message={activity.error ?? 'No answer from the watchdog.'} onRetry={activity.refresh} />
      ) : activity.data.days.length === 0 ? (
        <Empty title="No activity yet">It starts with the simulator's first transactions.</Empty>
      ) : (
        <Charts activity={activity.data} />
      )}
    </section>
  );
}

function Charts({ activity }: { activity: Activity }) {
  const columns = (pick: (day: Activity['days'][number]) => number, describe: (v: number) => string): Column[] =>
    activity.days.map((day) => ({
      key: day.date,
      value: pick(day),
      label: simulatedDate(day.date),
      tooltip: `${simulatedDate(day.date)}: ${describe(pick(day))}`,
    }));

  return (
    <>
      <div className="chart-stack">
        <ColumnChart
          title="Transactions"
          columns={columns((d) => d.transactions, (v) => `${count(v)} transactions`)}
          format={count}
          integers
        />
        <ColumnChart
          title="Spending (dollars)"
          columns={columns((d) => d.spendingCents / 100, (v) => `${money(v * 100)} spent`)}
          format={(v) => money(v * 100)}
        />
        <ColumnChart
          title="Alerts"
          columns={columns((d) => d.alerts, (v) => `${count(v)} alerts`)}
          format={count}
          integers
        />
      </div>
      <details className="table-view">
        <summary>Show as a table</summary>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">Day (simulated)</th>
                <th scope="col" className="number">Transactions</th>
                <th scope="col" className="number">Spending</th>
                <th scope="col" className="number">Alerts</th>
              </tr>
            </thead>
            <tbody>
              {[...activity.days].reverse().map((day) => (
                <tr key={day.date}>
                  <th scope="row">{simulatedDate(day.date)}</th>
                  <td className="number">{count(day.transactions)}</td>
                  <td className="number">{money(day.spendingCents)}</td>
                  <td className="number">{count(day.alerts)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </>
  );
}
