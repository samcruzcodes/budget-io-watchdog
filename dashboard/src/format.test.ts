import { describe, expect, it } from 'vitest';
import { categoryName, milliseconds, money, percent, ruleName, simulatedDate, simulatedDateTime } from './format';

describe('formatting', () => {
  it('shows cents as dollars without rounding errors', () => {
    expect(money(14_084)).toBe('$140.84');
    expect(money(129_900)).toBe('$1,299.00');
    expect(money(0)).toBe('$0.00');
  });

  it('shows "n/a" when a ratio has nothing to divide', () => {
    expect(percent(null)).toBe('n/a');
    expect(percent(0)).toBe('0%');
    expect(percent(2 / 3)).toBe('67%');
    expect(milliseconds(null)).toBe('n/a');
    expect(milliseconds(10.098)).toBe('10 ms');
    expect(milliseconds(4.21)).toBe('4.2 ms');
  });

  it('shows simulated times in UTC', () => {
    expect(simulatedDateTime('2026-03-08T18:57:45Z')).toBe('Mar 8, 2026, 18:57');
    expect(simulatedDate('2026-03-08')).toBe('Mar 8, 2026');
    expect(simulatedDate('2026-03-08T23:59:59Z')).toBe('Mar 8, 2026');
  });

  it('turns IDs into readable names without a hard-coded list', () => {
    expect(ruleName('duplicate-charge')).toBe('Duplicate charge');
    expect(ruleName('new-country')).toBe('New country');
    expect(categoryName('SUBSCRIPTIONS')).toBe('Subscriptions');
  });
});
