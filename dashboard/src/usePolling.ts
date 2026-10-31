import { useCallback, useEffect, useState } from 'react';

export interface Polled<T> {
  data: T | null;
  error: string | null; // the last attempt failed; `data` may still hold the previous good answer
  loading: boolean; // nothing has arrived yet
  refresh: () => void;
}

/**
 * Loads something now, and again every `intervalMs` (null: only on demand). Pass the values `load` depends on in
 * `deps`, so a new user or month loads fresh data.
 */
export function usePolling<T>(load: () => Promise<T>, intervalMs: number | null, deps: unknown[] = []): Polled<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const run = useCallback(load, deps);

  const refresh = useCallback(() => {
    run().then(
      (result) => {
        setData(result);
        setError(null);
        setLoading(false);
      },
      (failure: unknown) => {
        setError(failure instanceof Error ? failure.message : String(failure));
        setLoading(false);
      },
    );
  }, [run]);

  useEffect(() => {
    setLoading(true);
    setData(null);
    refresh();
    if (intervalMs === null) return undefined;
    const timer = setInterval(refresh, intervalMs);
    return () => clearInterval(timer);
  }, [refresh, intervalMs]);

  return { data, error, loading, refresh };
}
