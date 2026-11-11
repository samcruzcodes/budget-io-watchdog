import { useCallback, useEffect, useRef, useState } from 'react';

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
  // Answers can come back out of order: pick user A, then B, and A's slower answer could land last and be shown
  // under B's name. Each request gets a number, and only the newest one's answer is kept.
  const newestRequest = useRef(0);

  const refresh = useCallback(() => {
    const request = ++newestRequest.current;
    run().then(
      (result) => {
        if (request !== newestRequest.current) return;
        setData(result);
        setError(null);
        setLoading(false);
      },
      (failure: unknown) => {
        if (request !== newestRequest.current) return;
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
