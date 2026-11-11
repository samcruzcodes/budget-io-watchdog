// How numbers and times are shown. Money stays in whole cents until the last moment.

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export function money(cents: number): string {
  return usd.format(cents / 100);
}

/** A share between 0 and 1 as a whole percent; null means there's nothing to divide yet. */
export function percent(share: number | null): string {
  return share === null ? 'n/a' : `${Math.round(share * 100)}%`;
}

export function milliseconds(ms: number | null): string {
  if (ms === null) return 'n/a';
  return ms < 10 ? `${ms.toFixed(1)} ms` : `${Math.round(ms)} ms`;
}

export function perSecond(rate: number): string {
  return `${rate.toFixed(1)}/s`;
}

export function count(n: number): string {
  return new Intl.NumberFormat('en-US').format(n);
}

// Simulated people live in UTC, so simulated times are shown in UTC, wherever the viewer is.
const dateTime = new Intl.DateTimeFormat('en-US', {
  month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'UTC',
});
const dateOnly = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });

export function simulatedDateTime(iso: string): string {
  return dateTime.format(new Date(iso));
}

/** Accepts a full timestamp or a plain date (YYYY-MM-DD). */
export function simulatedDate(iso: string): string {
  return dateOnly.format(new Date(iso.length === 10 ? `${iso}T00:00:00Z` : iso));
}

/** "duplicate-charge" becomes "Duplicate charge". Rule IDs come from the API; nothing here lists them. */
export function ruleName(detector: string): string {
  const words = detector.replaceAll('-', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** "SUBSCRIPTIONS" becomes "Subscriptions". */
export function categoryName(category: string): string {
  return category.charAt(0) + category.slice(1).toLowerCase();
}

/** "30 days/min" (simulated days per real minute), or "max" when the simulator doesn't wait at all. */
export function speedLabel(daysPerMinute: number | null): string {
  if (daysPerMinute === null) return 'max';
  return `${daysPerMinute} ${daysPerMinute === 1 ? 'day' : 'days'}/min`;
}
