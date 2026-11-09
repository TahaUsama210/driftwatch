import { ChartLine, Table2 } from 'lucide-react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipContentProps,
} from 'recharts';
import { formatAxisTime, formatScore, formatShortDateTime } from '../../lib/format';
import { Button } from '../Button';

export interface ScorePoint {
  /** Epoch milliseconds. */
  t: number;
  score: number | null;
  /** Secondary line for the tooltip and table, e.g. "4 hosts". */
  note?: string;
}

export type ChartView = 'chart' | 'table';

interface ScoreTrendProps {
  points: readonly ScorePoint[];
  view: ChartView;
  /** Describes the series for screen readers and the table caption. */
  label: string;
  /** `stepAfter` for discrete scans (a score holds until the next scan). */
  curve?: 'linear' | 'stepAfter';
  /** Column header for `note` in the table view. */
  noteLabel?: string;
  height?: number;
}

const Y_TICKS = [0, 25, 50, 75, 100];
const AXIS_TICK = { fill: 'var(--color-ink-muted)', fontSize: 11 };

/** Score over time (0-100) as a single-series area chart, or its table twin. */
export function ScoreTrend({
  points,
  view,
  label,
  curve = 'linear',
  noteLabel = 'Detail',
  height = 240,
}: ScoreTrendProps) {
  if (view === 'table') return <ScoreTable points={points} label={label} noteLabel={noteLabel} />;

  const first = points[0]?.t ?? 0;
  const last = points.at(-1)?.t ?? 0;
  const span = last - first;

  return (
    <div style={{ height }} className="w-full">
      <AreaChart
        responsive
        data={points}
        style={{ width: '100%', height: '100%' }}
        margin={{ top: 8, right: 12, bottom: 0, left: 0 }}
        accessibilityLayer
        title={label}
      >
        <CartesianGrid vertical={false} stroke="var(--color-grid)" />
        <XAxis
          dataKey="t"
          type="number"
          scale="time"
          domain={['dataMin', 'dataMax']}
          tickFormatter={(value: number) => formatAxisTime(value, span)}
          tick={AXIS_TICK}
          tickLine={false}
          axisLine={{ stroke: 'var(--color-axis)' }}
          minTickGap={32}
        />
        <YAxis
          domain={[0, 100]}
          ticks={Y_TICKS}
          width={32}
          tick={AXIS_TICK}
          tickLine={false}
          axisLine={false}
        />
        <Tooltip
          content={ScoreTooltip}
          cursor={{ stroke: 'var(--color-border-strong)', strokeWidth: 1 }}
          isAnimationActive={false}
        />
        <Area
          type={curve}
          dataKey="score"
          name="Score"
          stroke="var(--color-series-1)"
          strokeWidth={2}
          strokeLinejoin="round"
          strokeLinecap="round"
          fill="var(--color-series-1)"
          fillOpacity={0.1}
          connectNulls={false}
          dot={false}
          activeDot={{
            r: 4,
            fill: 'var(--color-series-1)',
            stroke: 'var(--color-surface)',
            strokeWidth: 2,
          }}
          isAnimationActive={false}
        />
      </AreaChart>
    </div>
  );
}

function ScoreTooltip({ active, payload }: TooltipContentProps) {
  const point = payload[0]?.payload as ScorePoint | undefined;
  if (!active || !point) return null;
  return (
    <div className="rounded-md border border-border bg-surface px-3 py-2 text-xs shadow-md">
      <p className="text-base font-semibold text-ink tabular-nums">{formatScore(point.score)}</p>
      <p className="text-ink-secondary">{formatShortDateTime(point.t)}</p>
      {point.note ? <p className="text-ink-muted">{point.note}</p> : null}
    </div>
  );
}

function ScoreTable({
  points,
  label,
  noteLabel,
}: {
  points: readonly ScorePoint[];
  label: string;
  noteLabel: string;
}) {
  const hasNotes = points.some((point) => point.note);
  return (
    <div className="relative max-h-72 overflow-auto rounded-md border border-border">
      <table className="w-full text-left text-xs">
        <caption className="sr-only">{label}</caption>
        <thead className="sticky top-0 bg-sunken text-ink-secondary">
          <tr>
            <th scope="col" className="px-3 py-2 font-medium">
              Time
            </th>
            <th scope="col" className="px-3 py-2 text-right font-medium">
              Score
            </th>
            {hasNotes ? (
              <th scope="col" className="px-3 py-2 font-medium">
                {noteLabel}
              </th>
            ) : null}
          </tr>
        </thead>
        <tbody className="divide-y divide-border tabular-nums">
          {[...points].reverse().map((point) => (
            <tr key={point.t}>
              <td className="px-3 py-1.5 whitespace-nowrap text-ink-secondary">
                {formatShortDateTime(point.t)}
              </td>
              <td className="px-3 py-1.5 text-right font-medium text-ink">
                {formatScore(point.score)}
              </td>
              {hasNotes ? <td className="px-3 py-1.5 text-ink-secondary">{point.note}</td> : null}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Switches a chart card between the plot and its accessible table twin. */
export function ChartViewToggle({
  view,
  onChange,
}: {
  view: ChartView;
  onChange: (view: ChartView) => void;
}) {
  const showingTable = view === 'table';
  return (
    <Button
      size="sm"
      variant="ghost"
      aria-pressed={showingTable}
      onClick={() => {
        onChange(showingTable ? 'chart' : 'table');
      }}
    >
      {showingTable ? (
        <ChartLine aria-hidden className="size-3.5" />
      ) : (
        <Table2 aria-hidden className="size-3.5" />
      )}
      {showingTable ? 'Chart' : 'Table'}
    </Button>
  );
}
