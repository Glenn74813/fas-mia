import { useId, useState } from 'react';

// Geometry uses Number() for positions only; every amount shown is the API's exact decimal string.
// Chart colours from the validated reference palette (light surface): slot 1 blue, slot 2 orange. Values and labels
// always use text colours; the coloured mark only carries identity.
export const SERIES = ['#2a78d6', '#eb6834'] as const;

/** ₱1.2M, ₱350K, ₱900: for axes and tight labels only; full amounts appear in tooltips and tables. */
export function compactPeso(value: number): string {
  const abs = Math.abs(value);
  const sign = value < 0 ? '−' : '';
  if (abs >= 1e9) return `${sign}₱${(abs / 1e9).toFixed(1)}B`;
  if (abs >= 1e6) return `${sign}₱${(abs / 1e6).toFixed(1)}M`;
  if (abs >= 1e3) return `${sign}₱${(abs / 1e3).toFixed(0)}K`;
  return `${sign}₱${abs.toFixed(0)}`;
}

/** A nice upper bound and 4 ticks for a positive maximum. */
function ticks(max: number): number[] {
  if (max <= 0) return [0, 1];
  const step0 = max / 4;
  const mag = 10 ** Math.floor(Math.log10(step0));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= step0) ?? 10 * mag;
  return [0, step, step * 2, step * 3, step * 4];
}

export interface Point { label: string; value: number; display?: string }

/** Vertical columns over time for one series (one measure, one axis). Hover or focus a column for its exact amount. */
export function ColumnChart({ title, points, color = SERIES[0], valueName }: { title: string; points: Point[]; color?: string;
  valueName: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const id = useId();
  const max = Math.max(0, ...points.map((p) => p.value));
  const t = ticks(max);
  const top = t[t.length - 1] || 1;
  const W = 560;
  const H = 220;
  const pad = { l: 56, r: 12, t: 12, b: 28 };
  const plotW = W - pad.l - pad.r;
  const plotH = H - pad.t - pad.b;
  const band = plotW / Math.max(points.length, 1);
  const barW = Math.min(36, band * 0.56);
  const y = (v: number) => pad.t + plotH - (v / top) * plotH;
  return (
    <figure className="m-0" aria-labelledby={`${id}-t`}>
      <figcaption id={`${id}-t`} className="mb-1 text-sm font-medium text-slate-800">{title}</figcaption>
      <div className="relative">
        <svg viewBox={`0 0 ${W} ${H}`} className="h-auto w-full" role="img" aria-label={`${title}: ${points.map((p) => `${p.label} ${p.display ?? p.value}`).join(', ')}`}>
          {t.map((v) => (
            <g key={v}>
              <line x1={pad.l} x2={W - pad.r} y1={y(v)} y2={y(v)} stroke="#e2e8f0" strokeWidth={1} />
              <text x={pad.l - 6} y={y(v)} textAnchor="end" dominantBaseline="middle" fontSize={11} fill="#64748b">{compactPeso(v)}</text>
            </g>
          ))}
          {points.map((p, i) => {
            const x = pad.l + band * i + (band - barW) / 2;
            const h = Math.max(0, y(0) - y(p.value));
            const r = Math.min(4, h / 2);
            return (
              <g key={p.label} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} onFocus={() => setHover(i)}
                onBlur={() => setHover(null)} tabIndex={0} role="graphics-symbol" aria-label={`${p.label}: ${p.display ?? p.value}`}>
                {/* hit target wider than the mark */}
                <rect x={pad.l + band * i} y={pad.t} width={band} height={plotH} fill="transparent" />
                {h > 0 && <path d={`M${x},${y(0)} V${y(p.value) + r} Q${x},${y(p.value)} ${x + r},${y(p.value)} H${x + barW - r} Q${x + barW},${y(p.value)} ${x + barW},${y(p.value) + r} V${y(0)} Z`}
                  fill={color} opacity={hover === null || hover === i ? 1 : 0.55} />}
                <text x={pad.l + band * i + band / 2} y={H - 8} textAnchor="middle" fontSize={11} fill="#64748b">{p.label}</text>
              </g>
            );
          })}
          <line x1={pad.l} x2={W - pad.r} y1={y(0)} y2={y(0)} stroke="#94a3b8" strokeWidth={1} />
        </svg>
        {hover !== null && points[hover] && (
          <div role="status" className="pointer-events-none absolute rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-800 shadow"
            style={{ left: `${((pad.l + band * hover + band / 2) / W) * 100}%`, top: 0, transform: 'translateX(-50%)' }}>
            <span className="mr-1 inline-block h-2 w-2 rounded-sm" style={{ background: color }} />
            {points[hover].label}: <strong>{points[hover].display ?? points[hover].value}</strong> {valueName}
          </div>
        )}
      </div>
      <TableView caption={title} rows={points.map((p) => [p.label, p.display ?? String(p.value)])} headers={['Month', valueName]} />
    </figure>
  );
}

export interface Bar { label: string; value: number; display: string; note?: string }

/**
 * Horizontal bars for comparing a few named amounts (one series). Every bar is directly labelled with its value, so the
 * chart reads without hover; a `max` turns the bars into proportions of that maximum (for example 100 %).
 */
export function BarList({ title, bars, color = SERIES[0], max, valueName }: { title: string; bars: Bar[]; color?: string; max?: number;
  valueName: string }) {
  const top = max ?? Math.max(0, ...bars.map((b) => b.value));
  const id = useId();
  return (
    <figure className="m-0" aria-labelledby={`${id}-t`}>
      <figcaption id={`${id}-t`} className="mb-2 text-sm font-medium text-slate-800">{title}</figcaption>
      <ul className="space-y-2">
        {bars.map((b) => (
          <li key={b.label} className="text-sm">
            <div className="flex justify-between gap-2 text-slate-700"><span className="truncate" title={b.label}>{b.label}</span>
              <span className="whitespace-nowrap font-medium text-slate-900">{b.display}</span></div>
            <div className="mt-1.5 h-1.5 rounded-full bg-slate-100" title={`${b.label}: ${b.display}${b.note ? ` (${b.note})` : ''}`}>
              <div className="h-1.5 rounded-full" style={{ width: `${top > 0 ? Math.min(100, Math.max(0, (b.value / top) * 100)) : 0}%`, background: color }} />
            </div>
          </li>
        ))}
        {bars.length === 0 && <li className="text-sm text-slate-500">Nothing to show.</li>}
      </ul>
      <TableView caption={title} rows={bars.map((b) => [b.label, b.display])} headers={['', valueName]} />
    </figure>
  );
}

/** A headline figure with its label (and an optional qualifier). */
export function StatTile({ label, value, hint, tone }: { label: string; value: string; hint?: string; tone?: 'warning' }) {
  return (
    <div className="rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs">
      <div className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">{label}</div>
      <div className="mt-2 text-[1.7rem] font-semibold leading-none tracking-tight text-slate-900 tabular">{value}</div>
      {hint && <div className={`mt-1 text-xs ${tone === 'warning' ? 'text-amber-800' : 'text-slate-500'}`}>{tone === 'warning' && <span aria-hidden>⚠ </span>}{hint}</div>}
    </div>
  );
}

function TableView({ caption, headers, rows }: { caption: string; headers: string[]; rows: string[][] }) {
  return (
    <details className="mt-2 text-xs text-slate-600">
      <summary className="cursor-pointer select-none">Show as table</summary>
      <table className="mt-1 w-full"><caption className="sr-only">{caption}</caption>
        <thead><tr>{headers.map((h) => <th key={h} className="py-0.5 pr-2 text-left font-medium">{h}</th>)}</tr></thead>
        <tbody>{rows.map((r) => <tr key={r[0]} className="border-t border-slate-100">{r.map((c, i) => <td key={i} className={`py-0.5 pr-2 ${i > 0 ? 'text-right' : ''}`}>{c}</td>)}</tr>)}</tbody>
      </table>
    </details>
  );
}

