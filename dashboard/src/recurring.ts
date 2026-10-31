// How a recurring charge is described: its status in words, and its price, showing the change where there is one.

import type { RecurringCharge, RecurringStatus } from './api';
import { money } from './format';

const STATUS_LABELS: Record<RecurringStatus, string> = {
  ACTIVE: 'Steady',
  PRICE_UP: 'Price up',
  VARIES: 'Varies',
  OVERDUE: 'Overdue',
};

export function statusLabel(status: RecurringStatus): string {
  return STATUS_LABELS[status];
}

/** "$15.49" when the price held; "$17.99 → $19.99" when it changed, so a charge that keeps going up is visible. */
export function priceText(charge: RecurringCharge): string {
  if (charge.previousPriceCents === charge.priceCents) return money(charge.priceCents);
  if (charge.status === 'ACTIVE') return money(charge.priceCents); // steady means within 1%: not worth an arrow
  return `${money(charge.previousPriceCents)} → ${money(charge.priceCents)}`;
}

/** Roughly what the charges still running cost in a year. Overdue ones may have been cancelled, so they're left out. */
export function yearlyTotalCents(charges: RecurringCharge[]): number {
  return charges.filter((c) => c.status !== 'OVERDUE').reduce((sum, c) => sum + c.yearlyCostCents, 0);
}
