import { useState } from 'react';
import { api, type UserSummary } from '../../api';
import { HorizontalBars } from '../../charts/HorizontalBars';
import { Empty, ErrorState, Loading } from '../../components/States';
import { UserPicker } from '../../components/UserPicker';
import { money } from '../../format';
import { usePolling } from '../../usePolling';

/** One user's month, grouped the way Budget.io groups a budget. */
export function SpendingPanel() {
  const users = usePolling(api.users, null);

  return (
    <section className="panel" aria-labelledby="spending-title">
      <div className="panel-head">
        <h2 id="spending-title">Spending by Budget.io line</h2>
        <p className="panel-note">
          A simulated month for one user, grouped into the lines of the Budget.io planner. Food is groceries plus
          dining; TP Fund is shopping.
        </p>
      </div>
      {users.loading ? (
        <Loading what="users" />
      ) : !users.data ? (
        <ErrorState message={users.error ?? 'No answer from the watchdog.'} onRetry={users.refresh} />
      ) : users.data.length === 0 ? (
        <Empty title="No users yet" />
      ) : (
        <SpendingFor users={users.data} />
      )}
    </section>
  );
}

function SpendingFor({ users }: { users: UserSummary[] }) {
  const [user, setUser] = useState(users[0].userId);
  const newest = users.find((u) => u.userId === user)?.newestOccurredAt ?? users[0].newestOccurredAt;
  const [month, setMonth] = useState(newest.slice(0, 7)); // the user's newest simulated month, YYYY-MM
  const summary = usePolling(() => api.spendingSummary(user, month), null, [user, month]);

  return (
    <>
      <div className="toolbar">
        <UserPicker id="spending-user" users={users} value={user} onChange={setUser} />
        <div className="field">
          <label htmlFor="spending-month">Month (simulated)</label>
          <input id="spending-month" type="month" value={month} onChange={(event) => setMonth(event.target.value)} />
        </div>
      </div>
      {summary.loading ? (
        <Loading what="the month" />
      ) : !summary.data ? (
        <ErrorState message={summary.error ?? 'No answer from the watchdog.'} onRetry={summary.refresh} />
      ) : summary.data.spendingCents === 0 && summary.data.incomeCents === 0 ? (
        <Empty title="Nothing in this month">Pick a month this user has transactions in.</Empty>
      ) : (
        <>
          <p className="toolbar-note">
            Income <strong>{money(summary.data.incomeCents)}</strong>, spending{' '}
            <strong>{money(summary.data.spendingCents)}</strong>
          </p>
          <HorizontalBars
            title={`Spending by line, ${summary.data.month}`}
            bars={summary.data.lines.map((line) => ({ label: line.line, value: line.cents }))}
            format={money}
          />
        </>
      )}
    </>
  );
}
