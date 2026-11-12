import { useEffect, useMemo } from 'react';
import { RuleBadge } from '../components/RuleBadge';
import { Empty, Loading } from '../components/States';
import { alertsByTransaction } from '../feed';
import { categoryName, money, simulatedDateTime } from '../format';
import { useHashParams } from '../useHashView';
import { useLiveStream } from '../LiveStream';

export function LiveFeed() {
  const { feed, connection, loaded } = useLiveStream();
  const alerts = useMemo(() => alertsByTransaction(feed.alerts), [feed.alerts]);
  // "#feed?highlight=<transaction ID>" marks one row, if it's still among the newest: the Scenarios tab links here.
  const highlighted = useHashParams().get('highlight');
  const highlightedShown = highlighted !== null && feed.transactions.some((tx) => tx.transactionId === highlighted);
  useEffect(() => {
    if (highlightedShown) document.getElementById(`tx-${highlighted}`)?.scrollIntoView({ block: 'center' });
  }, [highlightedShown, highlighted]);

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
      {highlighted && !highlightedShown && (
        <p className="toolbar-note" role="status">
          The transaction that link pointed at isn't among the newest {feed.transactions.length} anymore. The Scenarios
          tab still lists it.
        </p>
      )}
      {feed.transactions.length === 0 ? (
        <Empty title="No transactions yet">The simulator starts publishing a few seconds after the stack is up.</Empty>
      ) : (
        <div className="table-scroll">
          <table data-testid="feed-table" className="stack-on-mobile">
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
                  <tr
                    key={tx.transactionId}
                    id={`tx-${tx.transactionId}`}
                    className={[raised.length > 0 ? 'row-flagged' : '', tx.transactionId === highlighted ? 'row-highlight' : '']
                      .join(' ')
                      .trim() || undefined}
                  >
                    <td className="nowrap" data-label="Time (simulated)">{simulatedDateTime(tx.occurredAt)}</td>
                    <td className="nowrap" data-label="Card">
                      <span>
                        {tx.cardId}
                        {tx.source === 'DEMO' && <span className="cell-note"> scenario</span>}
                      </span>
                    </td>
                    <td data-label="Merchant">
                      {tx.merchant}
                      {tx.country !== 'US' && <span className="cell-note"> {tx.country}</span>}
                    </td>
                    <td data-label="Category">{categoryName(tx.category)}</td>
                    <td className="number" data-label="Amount">{money(tx.amountCents)}</td>
                    <td data-label="Alerts">
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
