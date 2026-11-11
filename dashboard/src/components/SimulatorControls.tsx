import { useState } from 'react';
import { api } from '../api';
import { speedLabel } from '../format';
import { summarize, toggleLabel } from '../simulatorState';
import { usePolling } from '../usePolling';

const STATUS_EVERY_MS = 3000;

/**
 * Start, pause, resume, and change the speed of the simulator, through its control API. Pausing freezes simulated
 * time; resuming continues from the same moment. The speeds offered come from the simulator itself.
 *
 * Everything shown is the simulator's own answer. If it stops answering, the last answer is marked as unconfirmed
 * and the controls are switched off, instead of showing "running" for a simulator that may be gone.
 */
export function SimulatorControls() {
  const status = usePolling(api.simulator.status, STATUS_EVERY_MS);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  async function act(change: () => Promise<unknown>) {
    setBusy(true);
    setActionError(null);
    try {
      await change();
    } catch (failure) {
      setActionError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
      status.refresh(); // show the simulator's own answer, not a guess
    }
  }

  if (status.loading) return <span className="sim-controls cell-note">Simulator…</span>;
  if (!status.data) {
    return (
      <span className="sim-controls sim-unavailable" title={status.error ?? undefined} data-testid="simulator-state">
        Simulator controls unavailable
      </span>
    );
  }

  const stale = status.error !== null; // the last request failed; status.data is the previous answer
  const { daysPerMinute, speeds } = status.data;
  const paused = status.data.state === 'PAUSED';
  const summary = summarize(status.data, stale);

  return (
    <div className="sim-controls" role="group" aria-label="Simulator controls">
      <span className={`sim-state sim-${summary.tone}`} aria-live="polite" data-testid="simulator-state">
        {summary.text}
      </span>
      {summary.hint && <span className="sim-hint">{summary.hint}</span>}
      <button
        type="button"
        className="button button-small"
        disabled={busy || stale}
        onClick={() => act(paused ? api.simulator.resume : api.simulator.pause)}
        data-testid="simulator-toggle"
      >
        {toggleLabel(status.data)}
      </button>
      <label className="visually-hidden" htmlFor="simulator-speed">
        Simulator speed, in simulated days per real minute
      </label>
      <select
        id="simulator-speed"
        className="sim-speed"
        value={daysPerMinute ?? 'max'}
        disabled={busy || stale}
        onChange={(event) => act(() => api.simulator.setSpeed(Number(event.target.value)))}
        data-testid="simulator-speed"
      >
        {daysPerMinute === null && (
          <option value="max" disabled>
            {speedLabel(null)}
          </option>
        )}
        {speeds.map((speed) => (
          <option key={speed} value={speed}>
            {speedLabel(speed)}
          </option>
        ))}
      </select>
      {actionError && (
        <span className="sim-error" role="alert">
          {actionError}
        </span>
      )}
    </div>
  );
}
