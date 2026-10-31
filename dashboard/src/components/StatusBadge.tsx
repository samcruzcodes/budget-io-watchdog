import { describe, type Connection } from '../connection';

/** The stream's state in words and color. aria-live, so a screen reader hears when it drops or comes back. */
export function StatusBadge({ connection }: { connection: Connection }) {
  return (
    <span className={`status status-${connection}`} role="status" aria-live="polite" data-testid="connection-status">
      <span className="status-dot" aria-hidden="true" />
      {describe(connection)}
    </span>
  );
}
