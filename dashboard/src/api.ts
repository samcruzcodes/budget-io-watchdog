// The watchdog's REST API, typed by hand from the Java records (watchdog-service/.../api and transaction-schema).
// Times are ISO-8601 strings in UTC. Money is whole cents.

export interface Transaction {
  transactionId: string;
  userId: string;
  cardId: string;
  merchant: string;
  category: string;
  amountCents: number;
  country: string;
  occurredAt: string; // simulated time
  publishedAt: string; // real time
}

export interface Alert {
  alertId: string;
  transactionId: string;
  userId: string;
  cardId: string;
  detector: string; // a rule ID, like "duplicate-charge"
  issueType: string;
  explanation: string;
  occurredAt: string; // the transaction's simulated time
  detectedAt: string; // real time
}

export interface RuleScore {
  detector: string;
  issueType: string;
  alerts: number;
  truePositives: number;
  falsePositives: number;
  unlabeledAlerts: number;
  scenarios: number;
  caughtScenarios: number;
  falseNegatives: number;
  precision: number | null; // null: no labeled alerts yet
  recall: number | null; // null: no scenarios yet
}

export interface Stats {
  totals: { transactions: number; alerts: number };
  detectors: RuleScore[];
  throughput: { windowSeconds: number; transactions: number; perSecond: number };
  latency: { windowSeconds: number; samples: number; p50Ms: number | null; p95Ms: number | null; p99Ms: number | null };
}

export type RecurringStatus = 'ACTIVE' | 'PRICE_UP' | 'VARIES' | 'OVERDUE';

export interface RecurringCharge {
  cardId: string;
  merchant: string;
  category: string;
  priceCents: number;
  previousPriceCents: number;
  status: RecurringStatus;
  lastChargedAt: string;
  nextExpectedAt: string;
  yearlyCostCents: number;
}

export interface SpendingSummary {
  userId: string;
  month: string; // YYYY-MM
  incomeCents: number;
  spendingCents: number;
  lines: { line: string; cents: number }[];
}

export interface UserSummary {
  userId: string;
  cardIds: string[];
  transactions: number;
  newestOccurredAt: string;
}

export interface Activity {
  from: string | null; // YYYY-MM-DD, simulated
  to: string | null;
  days: { date: string; transactions: number; spendingCents: number; alerts: number }[];
}

/** What went wrong, in words a person can act on. The API's 400s explain themselves in `detail`. */
export class ApiError extends Error {}

async function get<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { headers: { Accept: 'application/json' } });
  } catch {
    throw new ApiError("Can't reach the watchdog. Is it running?");
  }
  if (!response.ok) {
    let detail = '';
    try {
      detail = ((await response.json()) as { detail?: string }).detail ?? '';
    } catch {
      // not a JSON error body, for example nginx answering while the watchdog is down
    }
    throw new ApiError(detail || `The watchdog answered ${response.status} for ${path}.`);
  }
  return (await response.json()) as T;
}

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

export const api = {
  stats: () => get<Stats>('/api/stats'),
  transactions: (limit: number, userId?: string) => get<Transaction[]>(`/api/transactions${query({ limit, userId })}`),
  alerts: (limit: number, detector?: string, userId?: string) =>
    get<Alert[]>(`/api/alerts${query({ limit, detector, userId })}`),
  recurring: (userId: string) => get<RecurringCharge[]>(`/api/recurring${query({ userId })}`),
  spendingSummary: (userId: string, month: string) =>
    get<SpendingSummary>(`/api/spending/summary${query({ userId, month })}`),
  users: () => get<UserSummary[]>('/api/users'),
  activity: (days: number) => get<Activity>(`/api/activity${query({ days })}`),
};
