// The live feed's data: the newest transactions and alerts, merged from the stream and from REST refills.
// Both sources can repeat things: after a reconnect the refill overlaps what the stream already sent, and an alert can
// rarely be published twice (Phase 3), so everything is keyed by its ID and kept once.

import type { Alert, Transaction } from './api';

export const FEED_LIMIT = 200;

export interface Feed {
  transactions: Transaction[]; // newest first, by simulated time
  alerts: Alert[]; // newest first, by simulated time
}

export const emptyFeed: Feed = { transactions: [], alerts: [] };

function newestFirst<T extends { occurredAt: string }>(id: (item: T) => string) {
  return (a: T, b: T) => b.occurredAt.localeCompare(a.occurredAt) || id(b).localeCompare(id(a));
}

function merge<T extends { occurredAt: string }>(current: T[], incoming: T[], id: (item: T) => string): T[] {
  if (incoming.length === 0) return current;
  const byId = new Map(current.map((item) => [id(item), item]));
  let changed = false;
  for (const item of incoming) {
    if (!byId.has(id(item))) {
      byId.set(id(item), item);
      changed = true;
    }
  }
  if (!changed) return current; // nothing new: keep the same array, so React skips a re-render
  return [...byId.values()].sort(newestFirst(id)).slice(0, FEED_LIMIT);
}

export function addTransactions(feed: Feed, incoming: Transaction[]): Feed {
  const transactions = merge(feed.transactions, incoming, (tx) => tx.transactionId);
  return transactions === feed.transactions ? feed : { ...feed, transactions };
}

export function addAlerts(feed: Feed, incoming: Alert[]): Feed {
  const alerts = merge(feed.alerts, incoming, (alert) => alert.alertId);
  return alerts === feed.alerts ? feed : { ...feed, alerts };
}

/** The alerts raised on each transaction, for the feed's badges. */
export function alertsByTransaction(alerts: Alert[]): Map<string, Alert[]> {
  const byTransaction = new Map<string, Alert[]>();
  for (const alert of alerts) {
    const list = byTransaction.get(alert.transactionId) ?? [];
    list.push(alert);
    byTransaction.set(alert.transactionId, list);
  }
  return byTransaction;
}
