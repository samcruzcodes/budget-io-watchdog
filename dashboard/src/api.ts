// The watchdog's REST API, typed by hand from the Java records (watchdog-service/.../api and transaction-schema).
// Times are ISO-8601 strings in UTC. Money is whole cents.

export interface Transaction {
  transactionId: string;
  source: string; // SIMULATOR for the seeded run, DEMO for a scenario someone triggered
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
  // Messages skipped because their transaction was already stored: in the last window, and since the watchdog started.
  duplicates: { windowSeconds: number; skipped: number; sinceStartup: number };
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

/** The simulator's clock, from its control API. `daysPerMinute` is null when it runs at "max" (no waiting). */
export interface SimulatorStatus {
  state: 'RUNNING' | 'PAUSED';
  daysPerMinute: number | null;
  simulatedNow: string;
  published: number; // by this simulator process
  speeds: number[]; // what can be chosen, in simulated days per real minute
  resumedFrom: string | null; // where it continued an earlier run after a restart; null for a new run
  pauseAfterDays: number; // it pauses by itself every this many simulated days; 0 = never
  pausedItself: boolean; // paused right now because those days ran out
}

/** Which demo scenarios the simulator offers, and whether they're turned on (SIM_SCENARIOS_ENABLED). */
export interface ScenarioCatalog {
  enabled: boolean;
  types: string[]; // issue types, like DUPLICATE_CHARGE
}

/** One transaction of a scenario run, as the simulator planned and published it. */
export interface ScenarioStep {
  transactionId: string;
  role: 'SETUP' | 'SUSPICIOUS'; // history the rule needs first, or part of the problem
  occurredAt: string; // simulated time
  merchant: string;
  category: string;
  amountCents: number;
  country: string;
  note: string;
}

/** What the simulator published for one run. `scenarioId` is how the watchdog's API finds it again. */
export interface ScenarioRun {
  scenarioId: string;
  type: string;
  userId: string;
  cardId: string;
  expectedDetector: string; // the rule that should fire
  steps: ScenarioStep[];
}

/** What the watchdog has stored for a scenario, and the alerts its rules raised on it. */
export interface ScenarioResult {
  scenarioId: string;
  transactions: Transaction[];
  alerts: Alert[];
}

/** What went wrong, in words a person can act on. The API's 400s explain themselves in `detail`. */
export class ApiError extends Error {}

async function get<T>(path: string): Promise<T> {
  return send<T>(path, { headers: { Accept: 'application/json' } });
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  return send<T>(path, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function send<T>(path: string, init: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, init);
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
  simulator: {
    status: () => get<SimulatorStatus>('/api/simulator/status'),
    pause: () => post<SimulatorStatus>('/api/simulator/pause'),
    resume: () => post<SimulatorStatus>('/api/simulator/resume'),
    setSpeed: (daysPerMinute: number) => post<SimulatorStatus>('/api/simulator/speed', { daysPerMinute }),
    scenarios: () => get<ScenarioCatalog>('/api/simulator/scenarios'),
    runScenario: (type: string) => post<ScenarioRun>('/api/simulator/scenarios', { type }),
  },
  scenarioResult: (scenarioId: string) => get<ScenarioResult>(`/api/scenarios/${scenarioId}`),
};
