import { describe, expect, it } from 'vitest';
import type { Alert, Transaction } from './api';
import { FEED_LIMIT, addAlerts, addTransactions, alertsByTransaction, emptyFeed } from './feed';

function tx(id: string, occurredAt: string): Transaction {
  return {
    source: 'SIMULATOR',
    transactionId: id, userId: 'user-0001', cardId: 'card-0001', merchant: 'Blue Bottle Coffee',
    category: 'DINING', amountCents: 545, country: 'US', occurredAt, publishedAt: '2026-10-30T12:00:00Z',
  };
}

function alert(id: string, transactionId: string, occurredAt: string): Alert {
  return {
    alertId: id, transactionId, userId: 'user-0001', cardId: 'card-0001', detector: 'duplicate-charge',
    issueType: 'DUPLICATE_CHARGE', explanation: 'Blue Bottle Coffee charged $5.45 twice, 40 seconds apart.',
    occurredAt, detectedAt: '2026-10-30T12:00:00Z',
  };
}

describe('the live feed', () => {
  it('keeps transactions newest first by simulated time', () => {
    const feed = addTransactions(emptyFeed, [tx('a', '2026-03-01T09:00:00Z'), tx('b', '2026-03-02T09:00:00Z')]);

    expect(feed.transactions.map((t) => t.transactionId)).toEqual(['b', 'a']);
  });

  it('keeps a transaction once when the stream and a refill both send it', () => {
    const fromStream = addTransactions(emptyFeed, [tx('a', '2026-03-01T09:00:00Z')]);
    const afterRefill = addTransactions(fromStream, [tx('a', '2026-03-01T09:00:00Z'), tx('b', '2026-02-28T09:00:00Z')]);

    expect(afterRefill.transactions.map((t) => t.transactionId)).toEqual(['a', 'b']);
  });

  it('keeps an alert once when it is published twice', () => {
    const feed = addAlerts(emptyFeed, [alert('x', 'a', '2026-03-01T09:00:00Z')]);
    const again = addAlerts(feed, [alert('x', 'a', '2026-03-01T09:00:00Z')]);

    expect(again.alerts).toHaveLength(1);
  });

  it('returns the same feed when nothing is new, so the page does not redraw', () => {
    const feed = addTransactions(emptyFeed, [tx('a', '2026-03-01T09:00:00Z')]);

    expect(addTransactions(feed, [tx('a', '2026-03-01T09:00:00Z')])).toBe(feed);
    expect(addAlerts(feed, [])).toBe(feed);
  });

  it(`keeps only the newest ${FEED_LIMIT}`, () => {
    const many = Array.from({ length: FEED_LIMIT + 50 }, (_, i) =>
      tx(`t${i}`, new Date(Date.UTC(2026, 0, 1) + i * 60_000).toISOString()));

    const feed = addTransactions(emptyFeed, many);

    expect(feed.transactions).toHaveLength(FEED_LIMIT);
    expect(feed.transactions[0].transactionId).toBe(`t${FEED_LIMIT + 49}`);
  });

  it('orders ties in simulated time the same way every time', () => {
    const same = '2026-03-01T09:00:00Z';
    const one = addTransactions(emptyFeed, [tx('a', same), tx('b', same)]);
    const other = addTransactions(emptyFeed, [tx('b', same), tx('a', same)]);

    expect(one.transactions).toEqual(other.transactions);
  });

  it('groups alerts by the transaction they were raised on', () => {
    const byTransaction = alertsByTransaction([
      alert('x', 'a', '2026-03-01T09:00:00Z'), alert('y', 'a', '2026-03-01T09:00:00Z'),
      alert('z', 'b', '2026-03-01T09:00:00Z'),
    ]);

    expect(byTransaction.get('a')?.map((a) => a.alertId)).toEqual(['x', 'y']);
    expect(byTransaction.get('c')).toBeUndefined();
  });
});
