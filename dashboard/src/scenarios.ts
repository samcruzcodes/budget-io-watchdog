// The Scenarios tab's words and its verdict logic, as plain data and functions so they're easy to test.
// Nothing here decides what gets detected: a verdict only compares what the simulator said it sent with what the
// watchdog's API says it stored and flagged.

import type { ScenarioResult, ScenarioRun } from './api';
import { ruleName } from './format';

export interface ScenarioCopy {
  story: string; // what happens, for someone new to the idea
  looksFor: string; // the rule's condition, with its default thresholds
  sends: string; // what pressing the button publishes
}

/** Keyed by the scenario type the simulator offers (an issue type, like DUPLICATE_CHARGE). */
export const SCENARIOS: Record<string, ScenarioCopy> = {
  CARD_TESTING: {
    story:
      'Someone with a stolen card number often checks that it works with a few tiny charges, then makes a big purchase.',
    looksFor: 'at least 3 charges of $2.00 or less in the hour before a charge of $100 or more on the same card.',
    sends: '4 tiny charges at different merchants a few minutes apart, then a $489.00 purchase.',
  },
  DUPLICATE_CHARGE: {
    story: "A shop's payment system hiccups and charges you twice for one purchase.",
    looksFor: 'the same merchant charging the same amount on the same card within 10 minutes.',
    sends: 'a $64.27 grocery charge, and the identical charge again 24 seconds later.',
  },
  NEW_COUNTRY: {
    story: "A charge appears from a country you've never used the card in, and you haven't booked any travel.",
    looksFor:
      'a country that is new for the card, once the card has at least 20 earlier charges, with no travel purchase in the last 30 days.',
    sends: '22 days of ordinary charges at home (the history the rule needs), then an $812.40 charge in France.',
  },
  PRICE_HIKE: {
    story: 'A subscription quietly starts costing more than it used to.',
    looksFor: 'two charges about a month apart at the same price, then a third that is more than 1% higher.',
    sends: 'Netflix at $15.49 two months ago and last month, then $18.49 today.',
  },
  SPENDING_SPIKE: {
    story: "One day's spending in a category is far above what's normal for you.",
    looksFor:
      "a day's dining or shopping total of at least $50 that is 3 standard deviations above the card's usual day, after at least 14 earlier days with spending in that category.",
    sends: '16 ordinary days of eating out at about $30 each, then three orders today adding up to $213.90.',
  },
  TRIAL_CONVERSION: {
    story: 'A free trial you forgot to cancel turns into a paid plan.',
    looksFor:
      'a charge of $1.00 or less, then the same merchant charging more than $1.00 and at least 5 times as much, 5 to 45 days later.',
    sends: 'a $0.00 trial signup 14 days ago, then a $10.99 charge from the same merchant today.',
  },
};

/** "DUPLICATE_CHARGE" -> "duplicate-charge": each rule's ID is its issue type in lower case with dashes. */
export function detectorFor(type: string): string {
  return type.toLowerCase().replaceAll('_', '-');
}

/** How long to wait for a run's transactions before saying they didn't arrive. Storing them normally takes under a second. */
export const GIVE_UP_AFTER_MS = 20_000;

export type VerdictState = 'running' | 'detected' | 'not-detected' | 'incomplete';

export interface Verdict {
  state: VerdictState;
  label: string; // the pill: one or two words
  headline: string;
  stored: number; // how many of the run's transactions the watchdog has stored
}

/**
 * Compares a run with what the watchdog has stored for its scenario ID.
 *
 * @param result   the latest answer from /api/scenarios/{id}, or null before the first one
 * @param waitedMs how long since the run was published
 */
export function verdict(run: ScenarioRun, result: ScenarioResult | null, waitedMs: number): Verdict {
  const storedIds = new Set((result?.transactions ?? []).map((tx) => tx.transactionId));
  const total = run.steps.length;
  const stored = run.steps.filter((step) => storedIds.has(step.transactionId)).length;

  if (stored < total) {
    if (waitedMs < GIVE_UP_AFTER_MS) {
      return {
        state: 'running',
        label: 'Running',
        headline: `Published to Kafka. The watchdog has stored ${stored} of ${total} transactions so far.`,
        stored,
      };
    }
    return {
      state: 'incomplete',
      label: 'Incomplete',
      headline:
        `Only ${stored} of ${total} transactions reached the watchdog's database after ${GIVE_UP_AFTER_MS / 1000} seconds. ` +
        'Is the watchdog running? The badge in the header shows whether it answers.',
      stored,
    };
  }

  // Every transaction is stored, and a transaction's alerts are saved in the same database transaction as the
  // charge itself, so the alerts listed now are final.
  const alerts = result?.alerts ?? [];
  const expected = ruleName(run.expectedDetector);
  if (alerts.some((alert) => alert.detector === run.expectedDetector)) {
    return { state: 'detected', label: 'Detected', headline: `The ${expected.toLowerCase()} rule fired.`, stored };
  }
  const others = [...new Set(alerts.map((alert) => ruleName(alert.detector).toLowerCase()))];
  return {
    state: 'not-detected',
    label: 'Not detected',
    headline:
      others.length === 0
        ? `All ${total} transactions were stored and checked, and no rule fired. The ${expected.toLowerCase()} rule's conditions weren't met (its thresholds may have been changed from the defaults).`
        : `The ${expected.toLowerCase()} rule didn't fire. Fired instead: ${others.join(', ')}.`,
    stored,
  };
}

/** Where to see one alert in the Alerts view: filtered to the demo user and the rule, with the alert highlighted. */
export function alertLink(run: ScenarioRun, alert: { alertId: string; detector: string }): string {
  return `#alerts?user=${encodeURIComponent(run.userId)}&rule=${encodeURIComponent(alert.detector)}&alert=${alert.alertId}`;
}

/** Where to see a transaction in the Live feed, highlighted if it's still among the newest rows. */
export function feedLink(transactionId: string): string {
  return `#feed?highlight=${transactionId}`;
}
