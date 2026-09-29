import { useState } from 'react';
import { linearScale, useWidth } from './useWidth';

export interface BarDatum { label: string; value: number; display?: string; highlight?: boolean; detail?: string }

/**
 * Horizontal bars. With `diverging`, bars grow left/right from zero in the positive/negative colors (e.g. factors
 * pushing toward one team or the other); otherwise one series color, with an optional highlighted bar.
 */
export function HBars({ data, diverging = false, domain, rowHeight = 30, labelWidth = 170, posLabel, negLabel }: {
  data: BarDatum[]; diverging?: boolean; domain?: [number, number]; rowHeight?: number; labelWidth?: number;
  posLabel?: string; negLabel?: string;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  // Diverging bars reserve room on the left for negative value labels so they never hit the row labels.
  const m = { top: diverging && (posLabel || negLabel) ? 20 : 4, right: 56, left: labelWidth + (diverging ? 40 : 0) };
  const maxAbs = Math.max(...data.map((d) => Math.abs(d.value)), 1e-9);
  const [d0, d1] = domain ?? (diverging ? [-maxAbs, maxAbs] : [0, maxAbs]);
  const x = linearScale(d0, d1, m.left, width - m.right);
  const height = m.top + data.length * rowHeight;
  const barH = Math.min(14, rowHeight - 12);

  return (
    <div className="chart" ref={ref}>
      <svg width={width} height={height} role="img" aria-label="Bar chart">
        {diverging && (
          <>
            <line x1={x(0)} x2={x(0)} y1={m.top - 4} y2={height} stroke="var(--neutral-mark)" />
            {negLabel && <text x={x(0) - 6} y={11} textAnchor="end" style={{ fontSize: 10 }}>← {negLabel}</text>}
            {posLabel && <text x={x(0) + 6} y={11} style={{ fontSize: 10 }}>{posLabel} →</text>}
          </>
        )}
        {data.map((d, i) => {
          const cy = m.top + i * rowHeight + rowHeight / 2;
          const x0 = x(diverging ? 0 : d0), x1 = x(d.value);
          const left = Math.min(x0, x1), w = Math.max(Math.abs(x1 - x0), 1);
          const fill = diverging
            ? d.value >= 0 ? 'var(--div-pos)' : 'var(--div-neg)'
            : d.highlight === false ? 'var(--neutral-mark)' : 'var(--series-1)';
          // 4px rounded data-end, square at the baseline.
          const r = Math.min(4, w / 2);
          const path = d.value >= 0 || !diverging
            ? `M${left},${cy - barH / 2}h${w - r}a${r},${r} 0 0 1 ${r},${r}v${barH - 2 * r}a${r},${r} 0 0 1 ${-r},${r}h${-(w - r)}z`
            : `M${left + w},${cy - barH / 2}h${-(w - r)}a${r},${r} 0 0 0 ${-r},${r}v${barH - 2 * r}a${r},${r} 0 0 0 ${r},${r}h${w - r}z`;
          const valueX = d.value >= 0 || !diverging ? left + w + 6 : left - 6;
          return (
            <g key={d.label} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
              <rect x={0} y={cy - rowHeight / 2} width={width} height={rowHeight} fill={hover === i ? 'var(--surface-2)' : 'transparent'} />
              <text x={labelWidth - 10} y={cy} dy="0.32em" textAnchor="end" style={{ fill: 'var(--text-secondary)', fontSize: 12 }}>{d.label}</text>
              <path d={path} fill={fill} />
              <text x={valueX} y={cy} dy="0.32em" textAnchor={d.value >= 0 || !diverging ? 'start' : 'end'}
                style={{ fill: 'var(--text-primary)', fontWeight: 600 }}>{d.display ?? d.value}</text>
            </g>
          );
        })}
      </svg>
      {hover != null && data[hover].detail && (
        <div className="tooltip" style={{ left: labelWidth, top: m.top + (hover + 1) * rowHeight }}>
          <div className="t-title">{data[hover].label}</div>
          <div className="t-row">{data[hover].detail}</div>
        </div>
      )}
    </div>
  );
}

/** Head-to-head probability split: the headline numbers plus one split bar. */
export function ProbabilitySplit({ home, away, pHome }: { home: string; away: string; pHome: number }) {
  const pctH = pHome * 100;
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: 10 }}>
        <div>
          <div className="muted" style={{ fontSize: 12 }}><span className="swatch" style={{ background: 'var(--series-1)' }} />{home} (home)</div>
          <div style={{ fontSize: 40, fontWeight: 700, letterSpacing: '-0.03em' }}>{pctH.toFixed(1)}%</div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div className="muted" style={{ fontSize: 12 }}><span className="swatch" style={{ background: 'var(--series-2)' }} />{away} (away)</div>
          <div style={{ fontSize: 40, fontWeight: 700, letterSpacing: '-0.03em' }}>{(100 - pctH).toFixed(1)}%</div>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 2, height: 12 }} role="img" aria-label={`${home} ${pctH.toFixed(1)}% vs ${away} ${(100 - pctH).toFixed(1)}%`}>
        <div style={{ width: `${pctH}%`, background: 'var(--series-1)', borderRadius: '4px 0 0 4px', transition: 'width .3s' }} />
        <div style={{ flex: 1, background: 'var(--series-2)', borderRadius: '0 4px 4px 0' }} />
      </div>
    </div>
  );
}
