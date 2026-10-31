import { describe, expect, it } from 'vitest';
import type { RecurringCharge, RecurringStatus } from './api';
import { priceText, statusLabel, yearlyTotalCents } from './recurring';

function charge(status: RecurringStatus, previous: number, price: number): RecurringCharge {
  return {
    cardId: 'card-0001', merchant: 'Netflix', category: 'SUBSCRIPTIONS', priceCents: price, previousPriceCents: previous,
    status, lastChargedAt: '2026-03-05T08:00:00Z', nextExpectedAt: '2026-04-04T08:00:00Z', yearlyCostCents: price * 12,
  };
}

describe('recurring charges', () => {
  it('names every status in words', () => {
    expect(statusLabel('ACTIVE')).toBe('Steady');
    expect(statusLabel('PRICE_UP')).toBe('Price up');
    expect(statusLabel('VARIES')).toBe('Varies');
    expect(statusLabel('OVERDUE')).toBe('Overdue');
  });

  it('shows both amounts when the price changed', () => {
    expect(priceText(charge('VARIES', 1_799, 1_999))).toBe('$17.99 → $19.99');
    expect(priceText(charge('PRICE_UP', 1_199, 1_399))).toBe('$11.99 → $13.99');
    expect(priceText(charge('VARIES', 9_377, 7_950))).toBe('$93.77 → $79.50');
  });

  it('shows one amount when the price held', () => {
    expect(priceText(charge('ACTIVE', 1_549, 1_549))).toBe('$15.49');
    expect(priceText(charge('ACTIVE', 10_000, 10_050))).toBe('$100.50'); // within 1% counts as steady
  });

  it('adds up a year of the charges still running', () => {
    expect(yearlyTotalCents([charge('ACTIVE', 1_549, 1_549), charge('OVERDUE', 999, 999)])).toBe(1_549 * 12);
  });
});
