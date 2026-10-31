/** One headline number with its label and, where it matters, which clock it uses. */
export function Metric({ label, value, note, testId }: { label: string; value: string; note?: string; testId?: string }) {
  return (
    <div className="metric">
      <p className="metric-label">{label}</p>
      <p className="metric-value" data-testid={testId}>
        {value}
      </p>
      {note && <p className="metric-note">{note}</p>}
    </div>
  );
}
