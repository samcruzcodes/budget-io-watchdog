// The live stream's connection state, as plain functions so the rules are easy to test.

export type Connection = 'connecting' | 'live' | 'reconnecting' | 'disconnected';

export interface ConnectionState {
  status: Connection;
  failures: number; // in a row, since the last successful connection
}

/** After this many failures in a row, say "disconnected" instead of "reconnecting" (it keeps trying either way). */
export const FAILURES_BEFORE_DISCONNECTED = 3;

export const initialConnection: ConnectionState = { status: 'connecting', failures: 0 };

export function connected(): ConnectionState {
  return { status: 'live', failures: 0 };
}

export function failed(state: ConnectionState): ConnectionState {
  const failures = state.failures + 1;
  return { status: failures >= FAILURES_BEFORE_DISCONNECTED ? 'disconnected' : 'reconnecting', failures };
}

/** Wait before the next try: 1 s, 2 s, 4 s, then 8 s at most, so a down server isn't hammered. */
export function retryDelayMs(failures: number): number {
  return Math.min(1000 * 2 ** Math.max(failures - 1, 0), 8000);
}

export function describe(connection: Connection): string {
  switch (connection) {
    case 'connecting':
      return 'Connecting';
    case 'live':
      return 'Live';
    case 'reconnecting':
      return 'Reconnecting';
    case 'disconnected':
      return 'Disconnected, retrying';
  }
}
