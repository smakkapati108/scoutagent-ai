import { useState } from 'react';
import { linearScale, niceTicks, useWidth } from './useWidth';

export interface LineSeries {
  name: string;
  color: string; // CSS color (a token var)
  points: { x: number; y: number; label?: string }[];
}

/** Multi-series line chart with a zero baseline, crosshair and tooltip. Optional faint per-point dots for raw values. */
export function LineChart({ series, height = 260, yLabel, xLabel, rawDots }: {
  series: LineSeries[]; height?: number; yLabel?: string; xLabel?: string;
  rawDots?: { x: number; y: number }[];
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hoverX, setHoverX] = useState<number | null>(null);
  const m = { top: 12, right: 48, bottom: 30, left: 40 };
  const all = series.flatMap((s) => s.points);
  if (all.length === 0) return <div className="empty">No data</div>;

  const ys = [...all.map((p) => p.y), ...(rawDots ?? []).map((p) => p.y), 0];
  const xs = all.map((p) => p.x);
  const yMin = Math.min(...ys), yMax = Math.max(...ys);
  const pad = (yMax - yMin) * 0.08 || 1;
  const yTicks = niceTicks(yMin - pad, yMax + pad);
  const x = linearScale(Math.min(...xs), Math.max(...xs), m.left, width - m.right);
  const y = linearScale(yTicks[0], yTicks[yTicks.length - 1], height - m.bottom, m.top);
  const xTicks = niceTicks(Math.min(...xs), Math.max(...xs), 8).filter((t) => t >= Math.min(...xs) && t <= Math.max(...xs));

  const onMove = (e: React.MouseEvent<SVGRectElement>) => {
    const box = (e.currentTarget.ownerSVGElement as SVGSVGElement).getBoundingClientRect();
    const px = e.clientX - box.left;
    const nearest = xs.reduce((best, v) => (Math.abs(x(v) - px) < Math.abs(x(best) - px) ? v : best), xs[0]);
    setHoverX(nearest);
  };
  const hovered = hoverX == null ? [] : series.map((s) => ({ s, p: s.points.find((p) => p.x === hoverX) })).filter((h) => h.p);

  return (
    <div className="chart" ref={ref}>
      <svg width={width} height={height} role="img" aria-label={`${series.map((s) => s.name).join(', ')} line chart`}>
        {yTicks.map((t) => (
          <g key={t}>
            <line x1={m.left} x2={width - m.right} y1={y(t)} y2={y(t)} stroke={t === 0 ? 'var(--neutral-mark)' : 'var(--grid)'} strokeWidth={1} />
            <text x={m.left - 8} y={y(t)} dy="0.32em" textAnchor="end">{t}</text>
          </g>
        ))}
        {xTicks.map((t) => (
          <text key={t} x={x(t)} y={height - m.bottom + 18} textAnchor="middle">{t}</text>
        ))}
        {yLabel && <text x={m.left} y={m.top - 2} dy="-0.2em" style={{ fontSize: 10 }}>{yLabel}</text>}
        {xLabel && <text x={width - m.right} y={height - 2} textAnchor="end" style={{ fontSize: 10 }}>{xLabel}</text>}
        {rawDots?.map((p, i) => <circle key={i} cx={x(p.x)} cy={y(p.y)} r={2} fill="var(--neutral-mark)" opacity={0.6} />)}
        {series.map((s) => (
          <path key={s.name} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round"
            d={s.points.map((p, i) => `${i ? 'L' : 'M'}${x(p.x).toFixed(1)},${y(p.y).toFixed(1)}`).join('')} />
        ))}
        {/* Direct labels at line ends (few series only). */}
        {series.length <= 4 && series.map((s) => {
          const last = s.points[s.points.length - 1];
          return <text key={s.name} x={x(last.x) + 6} y={y(last.y)} dy="0.32em" style={{ fill: 'var(--text-secondary)', fontWeight: 600 }}>{s.name}</text>;
        })}
        {hoverX != null && (
          <g>
            <line x1={x(hoverX)} x2={x(hoverX)} y1={m.top} y2={height - m.bottom} stroke="var(--text-muted)" strokeWidth={1} />
            {hovered.map(({ s, p }) => (
              <circle key={s.name} cx={x(p!.x)} cy={y(p!.y)} r={4.5} fill={s.color} stroke="var(--surface-1)" strokeWidth={2} />
            ))}
          </g>
        )}
        <rect x={m.left} y={m.top} width={Math.max(0, width - m.left - m.right)} height={height - m.top - m.bottom}
          fill="transparent" onMouseMove={onMove} onMouseLeave={() => setHoverX(null)} />
      </svg>
      {hoverX != null && hovered.length > 0 && (
        <div className="tooltip" style={{ left: Math.min(x(hoverX) + 12, width - 180), top: 8 }}>
          <div className="t-title">{xLabel ?? 'x'} {hoverX}</div>
          {hovered.map(({ s, p }) => (
            <div key={s.name} className="t-row">
              <span className="swatch" style={{ background: s.color }} />{s.name}: <b>{p!.y}</b>{p!.label ? ` · ${p!.label}` : ''}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
