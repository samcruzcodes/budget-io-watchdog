import { useEffect, useState } from 'react';
import { api, type ScenarioResult, type ScenarioRun } from '../api';
import { RuleBadge } from '../components/RuleBadge';
import { ErrorState, Loading } from '../components/States';
import { money, ruleName, simulatedDateTime } from '../format';
import { alertLink, detectorFor, feedLink, SCENARIOS, verdict, type Verdict } from '../scenarios';
import { usePolling } from '../usePolling';

const POLL_EVERY_MS = 400;

/** One scenario's latest run: nothing yet, a request that failed, or a run and what the watchdog has said so far. */
type Entry =
  | { phase: 'idle' }
  | { phase: 'starting' }
  | { phase: 'failed'; message: string }
  | { phase: 'ran'; run: ScenarioRun; result: ScenarioResult | null; startedAt: number };

// Kept outside the component, so a run is still here after a look at the Alerts or Live feed tab and back.
const latestRuns = new Map<string, Entry>();

/**
 * Trigger a detection scenario on purpose and watch the real system handle it. A button asks the simulator to publish
 * a scripted set of transactions to Kafka; this view then asks the watchdog what it stored and flagged for that
 * scenario ID. Nothing here creates an alert or decides a result.
 */
export function Scenarios() {
  const catalog = usePolling(api.simulator.scenarios, null);

  return (
    <>
      <section className="panel" aria-labelledby="scenarios-title">
        <div className="panel-head">
          <h2 id="scenarios-title">Scenarios</h2>
          <p className="panel-note">
            Trigger a problem on purpose and watch the pipeline catch it. Each button asks the simulator to publish a
            short, scripted set of transactions to Kafka on a brand-new demo card. The watchdog reads them like any
            others, stores them, and runs its six rules; what appears below is read back from its database, by the
            run's scenario ID. Rules judge by simulated time, so the history a rule needs is sent too, dated in the
            past.
          </p>
          <p className="panel-note">
            Demo transactions are marked (source DEMO, user demo-user) and left out of precision and recall. Scenarios
            also work while the simulator is paused, which keeps the feed quiet.
          </p>
        </div>
        {catalog.data && !catalog.data.enabled && (
          <p className="stale-note" role="status" data-testid="scenarios-off">
            Scenarios are turned off on this simulator. Start it with SIM_SCENARIOS_ENABLED=true (the demo preset does).
          </p>
        )}
      </section>
      {catalog.loading ? (
        <Loading what="scenarios" />
      ) : !catalog.data ? (
        <ErrorState
          message={`${catalog.error ?? 'No answer.'} Scenarios need the simulator's control API, which is reached through the dashboard on port 8083.`}
          onRetry={catalog.refresh}
        />
      ) : (
        catalog.data.types
          .filter((type) => type in SCENARIOS)
          .map((type) => <ScenarioCard key={type} type={type} enabled={catalog.data!.enabled} />)
      )}
    </>
  );
}

function ScenarioCard({ type, enabled }: { type: string; enabled: boolean }) {
  const [entry, setEntryState] = useState<Entry>(() => latestRuns.get(type) ?? { phase: 'idle' });
  const [now, setNow] = useState(() => Date.now());
  const copy = SCENARIOS[type];
  const detector = detectorFor(type);

  function setEntry(next: Entry) {
    latestRuns.set(type, next);
    setEntryState(next);
  }

  const current = entry.phase === 'ran' ? verdict(entry.run, entry.result, now - entry.startedAt) : null;
  const waiting = current?.state === 'running';
  const scenarioId = entry.phase === 'ran' ? entry.run.scenarioId : null;

  // While a run is in flight, keep asking the watchdog what it has stored for this scenario ID.
  useEffect(() => {
    if (!waiting || entry.phase !== 'ran') return undefined;
    const { run, startedAt } = entry;
    let stopped = false;
    const timer = setInterval(() => {
      api.scenarioResult(run.scenarioId).then(
        (result) => {
          if (stopped) return;
          setEntry({ phase: 'ran', run, result, startedAt });
          setNow(Date.now());
        },
        () => {
          if (!stopped) setNow(Date.now()); // the watchdog isn't answering: keep the clock moving toward giving up
        },
      );
    }, POLL_EVERY_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [waiting, scenarioId]);

  async function start() {
    setEntry({ phase: 'starting' });
    try {
      const run = await api.simulator.runScenario(type);
      setNow(Date.now());
      setEntry({ phase: 'ran', run, result: null, startedAt: Date.now() });
    } catch (failure) {
      setEntry({ phase: 'failed', message: failure instanceof Error ? failure.message : String(failure) });
    }
  }

  const busy = entry.phase === 'starting' || waiting;
  return (
    <section className="panel scenario" aria-labelledby={`scenario-${detector}`} data-testid={`scenario-${detector}`}>
      <div className="scenario-head">
        <div className="panel-head">
          <h3 id={`scenario-${detector}`}>{ruleName(detector)}</h3>
          <p>{copy.story}</p>
        </div>
        <div className="scenario-actions">
          <StatePill entry={entry} verdict={current} />
          <button type="button" className="button" disabled={!enabled || busy} onClick={start} data-testid="run-scenario">
            {entry.phase === 'idle' ? 'Run scenario' : 'Run again'}
          </button>
        </div>
      </div>
      <dl className="scenario-facts">
        <dt>The rule looks for</dt>
        <dd>{copy.looksFor}</dd>
        <dt>This scenario sends</dt>
        <dd>{copy.sends}</dd>
      </dl>
      {entry.phase === 'failed' && (
        <p className="scenario-verdict verdict-failed" role="alert">
          The simulator didn't run it: {entry.message}
        </p>
      )}
      {entry.phase === 'ran' && current && <Outcome run={entry.run} result={entry.result} verdict={current} />}
    </section>
  );
}

function StatePill({ entry, verdict }: { entry: Entry; verdict: Verdict | null }) {
  const [state, label] =
    entry.phase === 'idle'
      ? ['idle', 'Not run yet']
      : entry.phase === 'starting'
        ? ['running', 'Starting']
        : entry.phase === 'failed'
          ? ['failed', 'Failed']
          : [verdict!.state, verdict!.label];
  return (
    <span className={`pill run-${state}`} role="status" aria-live="polite" data-testid="scenario-state">
      {label}
    </span>
  );
}

function Outcome({ run, result, verdict }: { run: ScenarioRun; result: ScenarioResult | null; verdict: Verdict }) {
  const storedIds = new Set((result?.transactions ?? []).map((tx) => tx.transactionId));
  const alerts = result?.alerts ?? [];
  const stepById = new Map(run.steps.map((step) => [step.transactionId, step]));
  const setup = run.steps.filter((step) => step.role === 'SETUP');
  const suspicious = run.steps.filter((step) => step.role === 'SUSPICIOUS');
  const lastStep = run.steps[run.steps.length - 1];

  const rows = (steps: ScenarioRun['steps']) =>
    steps.map((step) => {
      const raised = alerts.filter((alert) => alert.transactionId === step.transactionId);
      return (
        <tr key={step.transactionId} className={raised.length > 0 ? 'row-flagged' : undefined}>
          <td className="nowrap" data-label="Time (simulated)">{simulatedDateTime(step.occurredAt)}</td>
          <td data-label="Merchant">
            {step.merchant}
            {step.country !== 'US' && <span className="cell-note"> {step.country}</span>}
          </td>
          <td className="number" data-label="Amount">{money(step.amountCents)}</td>
          <td data-label="What it is">{step.note}</td>
          <td data-label="Watchdog">
            <span>
              {storedIds.has(step.transactionId) ? 'Stored' : <span className="cell-note">Waiting</span>}
              {raised.map((alert) => (
                <span key={alert.alertId}>
                  {' '}
                  <RuleBadge detector={alert.detector} />
                </span>
              ))}
            </span>
          </td>
        </tr>
      );
    });

  return (
    <div className="scenario-outcome">
      <p className={`scenario-verdict verdict-${verdict.state}`} role="status" data-testid="scenario-verdict">
        {verdict.headline}
      </p>

      {alerts.length > 0 && (
        <div>
          <h4>What Watchdog said</h4>
          <ul className="plain-list" data-testid="scenario-alerts">
            {alerts.map((alert) => {
              const on = stepById.get(alert.transactionId);
              return (
                <li key={alert.alertId}>
                  <RuleBadge detector={alert.detector} /> {alert.explanation}
                  <span className="cell-note">
                    {' '}
                    Raised on {on ? `the ${money(on.amountCents)} charge at ${on.merchant}` : 'a transaction of this run'}
                    . <a href={alertLink(run, alert)}>See it in Alerts</a>
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      <div>
        <h4>What was sent</h4>
        <p className="panel-note">
          {run.steps.length} transactions on card {run.cardId}, scenario ID {run.scenarioId}. {verdict.stored} stored
          by the watchdog. <a href={feedLink(lastStep.transactionId)}>See the last one in the Live feed</a>
        </p>
        <div className="table-scroll">
          <table className="stack-on-mobile" data-testid="scenario-steps">
            <thead>
              <tr>
                <th scope="col">Time (simulated)</th>
                <th scope="col">Merchant</th>
                <th scope="col" className="number">Amount</th>
                <th scope="col">What it is</th>
                <th scope="col">Watchdog</th>
              </tr>
            </thead>
            <tbody>{rows(suspicious)}</tbody>
          </table>
        </div>
        {setup.length > 0 && (
          <details className="table-view">
            <summary>
              Show the {setup.length} earlier {setup.length === 1 ? 'transaction' : 'transactions'} sent as history
            </summary>
            <div className="table-scroll">
              <table className="stack-on-mobile">
                <thead>
                  <tr>
                    <th scope="col">Time (simulated)</th>
                    <th scope="col">Merchant</th>
                    <th scope="col" className="number">Amount</th>
                    <th scope="col">What it is</th>
                    <th scope="col">Watchdog</th>
                  </tr>
                </thead>
                <tbody>{rows(setup)}</tbody>
              </table>
            </div>
          </details>
        )}
      </div>
    </div>
  );
}
