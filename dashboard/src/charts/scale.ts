// Axis math for the hand-written charts: a "nice" top value and evenly spaced ticks.

/** The smallest of 1, 2, 2.5, 5, or 10 times a power of ten that's at least `max`, so axes end on round numbers. */
export function niceMax(max: number): number {
  if (max <= 0) return 1;
  const power = 10 ** Math.floor(Math.log10(max));
  for (const step of [1, 2, 2.5, 5, 10]) {
    if (step * power >= max) return step * power;
  }
  return 10 * power;
}

/**
 * Evenly spaced ticks from 0 to `top`, both ends included: four steps, or for whole-number data (counts), the first
 * of 4, 5, 2, or 1 steps that lands every tick on a whole number, so an axis of alerts never reads 1.3.
 */
export function ticks(top: number, integers = false): number[] {
  const steps = integers ? ([4, 5, 2, 1].find((n) => Number.isInteger(top / n)) ?? 1) : 4;
  return Array.from({ length: steps + 1 }, (_, i) => (top * i) / steps);
}

/** Compact axis labels: in full under 10,000, then 10K, 1.5M. */
export function compact(value: number): string {
  if (Math.abs(value) < 10_000) return new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 }).format(value);
  return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}
