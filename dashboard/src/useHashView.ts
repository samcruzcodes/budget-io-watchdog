import { useEffect, useState } from 'react';

/** The current view from the URL hash (#feed), so a refresh or a shared link opens the same view. */
export function useHashView<T extends string>(views: readonly T[], fallback: T): T {
  const read = () => {
    const hash = window.location.hash.slice(1) as T;
    return views.includes(hash) ? hash : fallback;
  };
  const [view, setView] = useState<T>(read);

  useEffect(() => {
    const onChange = () => setView(read());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  return view;
}
