import { useEffect, useState } from 'react';

// The hash is "#view" or "#view?name=value&...": the view to show, and optionally what to filter or highlight in it.

function hashParts(): { view: string; query: string } {
  const [view, query = ''] = window.location.hash.slice(1).split('?');
  return { view, query };
}

/** The current view from the URL hash (#feed), so a refresh or a shared link opens the same view. */
export function useHashView<T extends string>(views: readonly T[], fallback: T): T {
  const read = () => {
    const view = hashParts().view as T;
    return views.includes(view) ? view : fallback;
  };
  const [view, setView] = useState<T>(read);

  useEffect(() => {
    const onChange = () => setView(read());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  return view;
}

/** What comes after "?" in the hash, like the user and alert in "#alerts?user=demo-user&alert=...". */
export function useHashParams(): URLSearchParams {
  const [query, setQuery] = useState(() => hashParts().query);

  useEffect(() => {
    const onChange = () => setQuery(hashParts().query);
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  return new URLSearchParams(query);
}
