import { useState } from 'react';
import { api, type UserSummary } from '../api';
import { Empty, ErrorState, Loading, StaleNote } from '../components/States';
import { UserPicker } from '../components/UserPicker';
import { categoryName, money, simulatedDate } from '../format';
import { priceText, statusLabel, yearlyTotalCents } from '../recurring';
import { usePolling } from '../usePolling';

// Trial conversions aren't recurring charges (they're alerts), so they're shown beside the list, from this one rule.
const TRIAL_RULE = 'trial-conversion';
// The list changes as simulated months pass, so it's reloaded while the view is open.
const RECURRING_EVERY_MS = 15_000;

export function Recurring() {
  const users = usePolling(api.users, null);

  if (users.loading) return <Loading what="users" />;
  if (!users.data) return <ErrorState message={users.error ?? 'No answer from the watchdog.'} onRetry={users.refresh} />;
  if (users.data.length === 0) {
    return <Empty title="No users yet">They appear as soon as the simulator publishes its first transactions.</Empty>;
  }
  return <RecurringFor users={users.data} />;
}

function RecurringFor({ users }: { users: UserSummary[] }) {
  const [user, setUser] = useState(users[0].userId);
  const charges = usePolling(() => api.recurring(user), RECURRING_EVERY_MS, [user]);
  const trials = usePolling(() => api.alerts(50, TRIAL_RULE, user), RECURRING_EVERY_MS, [user]);

  return (
    <>
      <section className="panel" aria-labelledby="recurring-title">
        <div className="panel-head">
          <h2 id="recurring-title">Recurring charges</h2>
          <p className="panel-note">
            Charges about a month apart, worked out from the last 105 simulated days. Steady: the same price. Price up:
            steady, then more than 1% higher (what the price-hike rule alerts on). Varies: monthly, but the price
            moves. Overdue: no charge for over 35 days.
          </p>
        </div>
        <div className="toolbar">
          <UserPicker id="recurring-user" users={users} value={user} onChange={setUser} />
          {charges.data && charges.data.length > 0 && (
            <p className="toolbar-note">
              About <strong>{money(yearlyTotalCents(charges.data))}</strong> a year, not counting overdue charges.
            </p>
          )}
        </div>
        {charges.data && charges.error && <StaleNote message={charges.error} />}
        {charges.loading ? (
          <Loading what="recurring charges" />
        ) : !charges.data ? (
          <ErrorState message={charges.error ?? 'No answer from the watchdog.'} onRetry={charges.refresh} />
        ) : charges.data.length === 0 ? (
          <Empty title="No recurring charges yet">It takes two charges about a month apart, so give it a minute.</Empty>
        ) : (
          <div className="table-scroll">
            <table data-testid="recurring-table" className="stack-on-mobile">
              <thead>
                <tr>
                  <th scope="col">Merchant</th>
                  <th scope="col">Status</th>
                  <th scope="col" className="number">Price</th>
                  <th scope="col" className="number">Per year</th>
                  <th scope="col">Next expected</th>
                  <th scope="col">Last charge</th>
                  <th scope="col">Category</th>
                </tr>
              </thead>
              <tbody>
                {charges.data.map((charge) => (
                  <tr key={`${charge.cardId}/${charge.merchant}`}>
                    <th scope="row">{charge.merchant}</th>
                    <td data-label="Status">
                      <span className={`pill pill-${charge.status.toLowerCase()}`}>{statusLabel(charge.status)}</span>
                    </td>
                    <td className="number" data-label="Price">{priceText(charge)}</td>
                    <td className="number" data-label="Per year">{money(charge.yearlyCostCents)}</td>
                    <td className="nowrap" data-label="Next expected">{simulatedDate(charge.nextExpectedAt)}</td>
                    <td className="nowrap" data-label="Last charge">{simulatedDate(charge.lastChargedAt)}</td>
                    <td data-label="Category">{categoryName(charge.category)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <section className="panel" aria-labelledby="trials-title">
        <div className="panel-head">
          <h2 id="trials-title">Free trials that turned into paid plans</h2>
          <p className="panel-note">Flagged by the trial-conversion rule for this user. Times are simulated (UTC).</p>
        </div>
        {trials.loading ? (
          <Loading what="trial conversions" />
        ) : !trials.data ? (
          <ErrorState message={trials.error ?? 'No answer from the watchdog.'} onRetry={trials.refresh} />
        ) : trials.data.length === 0 ? (
          <Empty title="None flagged for this user" />
        ) : (
          <ul className="plain-list">
            {trials.data.map((alert) => (
              <li key={alert.alertId}>
                <span className="cell-note">{simulatedDate(alert.occurredAt)}</span> {alert.explanation}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
