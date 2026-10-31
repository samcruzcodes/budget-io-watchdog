import { useMemo } from 'react';
import { RuleBadge } from '../components/RuleBadge';
import { Empty, Loading } from '../components/States';
import { alertsByTransaction } from '../feed';
import { categoryName, money, simulatedDateTime } from '../format';
import { useLiveStream } from '../LiveStream';

export function LiveFeed() {
  const { feed, connection, loaded } = useLiveStream();
  const alerts = useMemo(() => alertsByTransaction(feed.alerts), [feed.alerts]);

  if (!loaded) return <Loading what="recent transactions" />;
  return (
    <section className="panel" aria-labelledby="feed-title">
      <div className="panel-head">
        <h2 id="feed-title">Live transactions</h2>
        <p className="panel-note">
          Newest first, as the watchdog stores them. Times are simulated (UTC). New rows appear on their own.
        </p>
      </div>
      {connection.status === 'disconnected' && (
        <p className="stale-note" role="status">
          The live stream is down, so new transactions won't appear until it reconnects. It keeps trying.
        </p>
      )}
      {feed.transactions.length === 0 ? (
        <Empty title="No transactions yet">The simulator starts publishing a few seconds after the stack is up.</Empty>
      ) : (
        <div className="table-scroll">
          <table data-testid="feed-table">
            <thead>
              <tr>
                <th scope="col">Time (simulated)</th>
                <th scope="col">Card</th>
                <th scope="col">Merchant</th>
                <th scope="col">Category</th>
                <th scope="col" className="number">Amount</th>
                <th scope="col">Alerts</th>
              </tr>
            </thead>
            <tbody>
              {feed.transactions.map((tx) => {
                const raised = alerts.get(tx.transactionId) ?? [];
                return (
                  <tr key={tx.transactionId} className={raised.length > 0 ? 'row-flagged' : undefined}>
                    <td className="nowrap">{simulatedDateTime(tx.occurredAt)}</td>
                    <td className="nowrap">{tx.cardId}</td>
                    <td>
                      {tx.merchant}
                      {tx.country !== 'US' && <span className="cell-note"> {tx.country}</span>}
                    </td>
                    <td>{categoryName(tx.category)}</td>
                    <td className="number">{money(tx.amountCents)}</td>
                    <td>
                      {raised.length === 0 ? (
                        <span className="cell-note">None</span>
                      ) : (
                        raised.map((alert) => <RuleBadge key={alert.alertId} detector={alert.detector} />)
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
