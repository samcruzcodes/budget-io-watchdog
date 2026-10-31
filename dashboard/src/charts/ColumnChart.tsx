import { compact, niceMax, ticks } from './scale';
import { useWidth } from './useWidth';

export interface Column {
  key: string;
  value: number;
  label: string; // shown under the first and last columns
  tooltip: string; // the full value, for hover and screen readers
}

const HEIGHT = 150;
const LEFT = 44; // room for the axis labels
const BOTTOM = 22; // room for the first and last dates
const TOP = 8;
const MAX_BAR = 24;

/**
 * One series of columns over time, drawn in SVG by hand at the container's real width: a single color (the title says what it is, so no legend),
 * a recessive axis, columns at most 24 px wide with a 4 px rounded top and a square base, and a tooltip on each.
 */
export function ColumnChart({ title, columns, format, integers = false }: {
  title: string;
  columns: Column[];
  format: (v: number) => string;
  integers?: boolean; // whole-number data (counts): ticks stay whole numbers
}) {
  const { ref, width } = useWidth<HTMLDivElement>();
  return (
    <figure className="chart">
      <figcaption className="chart-title">{title}</figcaption>
      <div ref={ref}>{width > 0 && <Columns title={title} columns={columns} width={width} integers={integers} />}</div>
      <p className="visually-hidden">
        {title}: highest {format(Math.max(0, ...columns.map((c) => c.value)))}.
      </p>
    </figure>
  );
}

function Columns({ title, columns, width, integers }: {
  title: string;
  columns: Column[];
  width: number;
  integers: boolean;
}) {
  const top = niceMax(Math.max(0, ...columns.map((c) => c.value)));
  const plotWidth = width - LEFT;
  const plotHeight = HEIGHT - TOP - BOTTOM;
  const slot = plotWidth / Math.max(columns.length, 1);
  const barWidth = Math.min(MAX_BAR, Math.max(slot - 2, 1)); // at least a 2 px gap between neighbors
  const y = (value: number) => TOP + plotHeight - (value / top) * plotHeight;

  return (
    <svg width={width} height={HEIGHT} role="img" aria-label={title} className="chart-svg">
      {ticks(top, integers).map((tick) => (
        <g key={tick}>
          <line x1={LEFT} x2={width} y1={y(tick)} y2={y(tick)} className="chart-gridline" />
          <text x={LEFT - 6} y={y(tick)} className="chart-axis" textAnchor="end" dominantBaseline="middle">
            {compact(tick)}
          </text>
        </g>
      ))}
      {columns.map((column, i) => {
        const x = LEFT + i * slot + (slot - barWidth) / 2;
        const height = y(0) - y(column.value);
        return (
          <g key={column.key}>
            {/* A tall invisible target, so small columns are easy to hover. */}
            <rect x={LEFT + i * slot} y={TOP} width={slot} height={plotHeight} className="chart-hit">
              <title>{column.tooltip}</title>
            </rect>
            {height > 0 && <path d={roundedTop(x, y(column.value), barWidth, height)} className="chart-bar" />}
          </g>
        );
      })}
      {columns.length > 0 && (
        <>
          <text x={LEFT} y={HEIGHT - 4} className="chart-axis">
            {columns[0].label}
          </text>
          <text x={width} y={HEIGHT - 4} className="chart-axis" textAnchor="end">
            {columns[columns.length - 1].label}
          </text>
        </>
      )}
    </svg>
  );
}

/** A column with a 4 px rounded top and a square base (it grows from the baseline). */
function roundedTop(x: number, top: number, width: number, height: number): string {
  const r = Math.min(4, width / 2, height);
  const bottom = top + height;
  return `M${x},${bottom} V${top + r} Q${x},${top} ${x + r},${top} H${x + width - r} Q${x + width},${top} ${x + width},${top + r} V${bottom} Z`;
}
