import { describe, expect, it } from 'vitest';
import type { Alert, ScenarioResult, ScenarioRun, Transaction } from './api';
import { alertLink, detectorFor, feedLink, firstRealUser, GIVE_UP_AFTER_MS, SCENARIOS, verdict } from './scenarios';

const run: ScenarioRun = {
  scenarioId: '8c3c8674-0000-4000-8000-000000000000',
  type: 'DUPLICATE_CHARGE',
  userId: 'demo-user',
  cardId: 'demo-8c3c8674',
  expectedDetector: 'duplicate-charge',
  steps: [
    step('tx-1', 'SETUP', '2027-06-05T10:09:26Z'),
    step('tx-2', 'SUSPICIOUS', '2027-06-05T10:09:50Z'),
  ],
};

function step(transactionId: string, role: 'SETUP' | 'SUSPICIOUS', occurredAt: string) {
  return {
    transactionId, role, occurredAt, merchant: 'Whole Foods Market', category: 'GROCERIES', amountCents: 6427,
    country: 'US', note: '',
  };
}

function stored(...ids: string[]): Transaction[] {
  return ids.map((transactionId) => ({ transactionId }) as Transaction);
}

function alert(detector: string, transactionId = 'tx-2'): Alert {
  return { alertId: `alert-${detector}`, transactionId, detector, explanation: 'why' } as Alert;
}

function result(transactions: Transaction[], alerts: Alert[] = []): ScenarioResult {
  return { scenarioId: run.scenarioId, transactions, alerts };
}

describe('scenario verdict', () => {
  it('is running until every transaction of the run is stored', () => {
    expect(verdict(run, null, 0).state).toBe('running');
    const partly = verdict(run, result(stored('tx-1')), 800);
    expect(partly.state).toBe('running');
    expect(partly.headline).toContain('1 of 2');
  });

  it('says the rule fired when the expected rule raised an alert', () => {
    const done = verdict(run, result(stored('tx-1', 'tx-2'), [alert('duplicate-charge')]), 600);
    expect(done).toMatchObject({ state: 'detected', label: 'Detected', stored: 2 });
    expect(done.headline).toBe('The duplicate charge rule fired.');
  });

  it('reports honestly when everything arrived and nothing fired', () => {
    const done = verdict(run, result(stored('tx-1', 'tx-2')), 600);
    expect(done.state).toBe('not-detected');
    expect(done.headline).toContain('no rule fired');
  });

  it('names the rules that fired instead of the expected one', () => {
    const done = verdict(run, result(stored('tx-1', 'tx-2'), [alert('spending-spike'), alert('spending-spike')]), 600);
    expect(done.state).toBe('not-detected');
    expect(done.headline).toBe("The duplicate charge rule didn't fire. Fired instead: spending spike.");
  });

  it("gives up after 20 seconds and says what didn't arrive", () => {
    const late = verdict(run, result(stored('tx-1')), GIVE_UP_AFTER_MS);
    expect(late.state).toBe('incomplete');
    expect(late.headline).toContain('Only 1 of 2');
  });

  it("ignores stored transactions that aren't part of the run", () => {
    expect(verdict(run, result(stored('tx-1', 'someone-elses')), 500).state).toBe('running');
  });

  it('never calls a run detected before all of it is stored, even if an alert is already there', () => {
    // The alert is real, but the run isn't finished: a later transaction could still add to the picture.
    expect(verdict(run, result(stored('tx-2'), [alert('duplicate-charge')]), 300).state).toBe('running');
  });
});

describe('scenario copy and links', () => {
  it('has words for all six rules', () => {
    expect(Object.keys(SCENARIOS)).toEqual([
      'CARD_TESTING', 'DUPLICATE_CHARGE', 'NEW_COUNTRY', 'PRICE_HIKE', 'SPENDING_SPIKE', 'TRIAL_CONVERSION',
    ]);
  });

  it('opens per-user views on a simulated person, not the demo user', () => {
    const users = [{ userId: 'demo-user' }, { userId: 'user-0001' }, { userId: 'user-0002' }];
    expect(firstRealUser(users).userId).toBe('user-0001');
    expect(firstRealUser([{ userId: 'demo-user' }]).userId).toBe('demo-user'); // nobody else yet
  });

  it('turns a scenario type into its rule ID', () => {
    expect(detectorFor('TRIAL_CONVERSION')).toBe('trial-conversion');
  });

  it('links an alert to the Alerts view, filtered and highlighted', () => {
    expect(alertLink(run, alert('duplicate-charge'))).toBe(
      '#alerts?user=demo-user&rule=duplicate-charge&alert=alert-duplicate-charge',
    );
    expect(feedLink('tx-2')).toBe('#feed?highlight=tx-2');
  });
});
