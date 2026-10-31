export interface Bar {
  label: string;
  value: number;
}

/**
 * Horizontal bars for comparing a few named amounts, largest first, with the value at each bar's tip. Plain HTML and
 * CSS widths rather than SVG, so the labels wrap and read like text.
 */
export function HorizontalBars({ title, bars, format }: { title: string; bars: Bar[]; format: (v: number) => string }) {
  const sorted = [...bars].sort((a, b) => b.value - a.value);
  const max = Math.max(0, ...sorted.map((b) => b.value));
  return (
    <figure className="chart">
      <figcaption className="chart-title">{title}</figcaption>
      <dl className="hbars">
        {sorted.map((bar) => (
          <div key={bar.label} className="hbar-row">
            <dt className="hbar-label">{bar.label}</dt>
            <dd className="hbar-track">
              <span
                className="hbar-fill"
                style={{ width: max === 0 ? '0%' : `${(bar.value / max) * 100}%` }}
                aria-hidden="true"
              />
              <span className="hbar-value">{format(bar.value)}</span>
            </dd>
          </div>
        ))}
      </dl>
    </figure>
  );
}
