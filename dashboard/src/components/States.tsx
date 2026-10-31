// What a section shows while there's no data to show.

import type { ReactNode } from 'react';

export function Loading({ what }: { what: string }) {
  return (
    <p className="state" role="status" aria-busy="true">
      Loading {what}…
    </p>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="state" role="status">
      <p className="state-title">{title}</p>
      {children && <p>{children}</p>}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="state state-error" role="alert">
      <p className="state-title">Couldn't load this</p>
      <p>{message}</p>
      {onRetry && (
        <button type="button" className="button" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

/** A quiet note above data that's still shown but couldn't be refreshed. */
export function StaleNote({ message }: { message: string }) {
  return (
    <p className="stale-note" role="status">
      Showing the last data received. {message}
    </p>
  );
}
