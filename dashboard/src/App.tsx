import { SimulatorControls } from './components/SimulatorControls';
import { StatusBadge } from './components/StatusBadge';
import { simulatedDate } from './format';
import { useLiveStream } from './LiveStream';
import { useHashView } from './useHashView';
import { Alerts } from './views/Alerts';
import { Analytics } from './views/Analytics';
import { LiveFeed } from './views/LiveFeed';
import { Overview } from './views/Overview';
import { Recurring } from './views/Recurring';
import { Scenarios } from './views/Scenarios';

const VIEWS = [
  { id: 'overview', label: 'Overview', render: () => <Overview /> },
  { id: 'feed', label: 'Live feed', render: () => <LiveFeed /> },
  { id: 'alerts', label: 'Alerts', render: () => <Alerts /> },
  { id: 'analytics', label: 'Analytics', render: () => <Analytics /> },
  { id: 'recurring', label: 'Recurring', render: () => <Recurring /> },
  { id: 'scenarios', label: 'Scenarios', render: () => <Scenarios /> },
] as const;

type ViewId = (typeof VIEWS)[number]['id'];

export function App() {
  const view = useHashView<ViewId>(VIEWS.map((v) => v.id), 'overview');
  const { feed, connection } = useLiveStream();
  const newest = feed.transactions[0]?.occurredAt;

  return (
    <div className="app">
      <header className="header">
        <div className="header-inner">
          <div className="brand">
            <h1>
              Budget.io <span className="brand-accent">Watchdog</span>
            </h1>
            <p className="brand-note">Card transactions, checked as they arrive</p>
          </div>
          <div className="header-status">
            <SimulatorControls />
            <p className="sim-date" data-testid="simulated-date">
              Simulated date <strong>{newest ? simulatedDate(newest) : 'n/a'}</strong>
            </p>
            <StatusBadge connection={connection.status} />
          </div>
        </div>
        <nav className="tabs" aria-label="Views">
          {VIEWS.map((v) => (
            <a key={v.id} href={`#${v.id}`} className="tab" aria-current={view === v.id ? 'page' : undefined}>
              {v.label}
            </a>
          ))}
        </nav>
      </header>
      <main className="main">{VIEWS.find((v) => v.id === view)?.render()}</main>
    </div>
  );
}
