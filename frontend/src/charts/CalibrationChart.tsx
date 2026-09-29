import { useState } from 'react';
import { linearScale, useWidth } from './useWidth';

/** Predicted vs actual home-win rate per probability bin. Dots on the diagonal = perfectly calibrated. */
export function CalibrationChart({ bins, height = 260 }: {
  bins: { bin: number; predicted: number; actual: number; games: number }[]; height?: number;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const m = { top: 12, right: 16, bottom: 32, left: 40 };
  const size = Math.min(width - m.left - m.right, height - m.top - m.bottom);
  const x = linearScale(0, 1, m.left, m.left + size);
  const y = linearScale(0, 1, m.top + size, m.top);
  const ticks = [0, 0.25, 0.5, 0.75, 1];
  const maxGames = Math.max(...bins.map((b) => b.games));
  const h = bins.find((b) => b.bin === hover);

  return (
    <div className="chart" ref={ref}>
      <svg width={width} height={size + m.top + m.bottom} role="img" aria-label="Calibration chart">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={x(0)} x2={x(1)} y1={y(t)} y2={y(t)} stroke="var(--grid)" />
            <text x={x(0) - 8} y={y(t)} dy="0.32em" textAnchor="end">{t * 100}%</text>
            <text x={x(t)} y={y(0) + 18} textAnchor="middle">{t * 100}%</text>
          </g>
        ))}
        <line x1={x(0)} y1={y(0)} x2={x(1)} y2={y(1)} stroke="var(--neutral-mark)" strokeWidth={1} />
        <text x={x(0.62)} y={y(0.5)} style={{ fontSize: 10 }}>perfect calibration</text>
        <path fill="none" stroke="var(--series-1)" strokeWidth={2}
          d={bins.map((b, i) => `${i ? 'L' : 'M'}${x(b.predicted)},${y(b.actual)}`).join('')} />
        {bins.map((b) => (
          <g key={b.bin} onMouseEnter={() => setHover(b.bin)} onMouseLeave={() => setHover(null)}>
            <circle cx={x(b.predicted)} cy={y(b.actual)} r={16} fill="transparent" />
            <circle cx={x(b.predicted)} cy={y(b.actual)} r={4 + 4 * Math.sqrt(b.games / maxGames)}
              fill="var(--series-1)" stroke="var(--surface-1)" strokeWidth={2} />
          </g>
        ))}
        <text x={x(0.5)} y={y(0) + 30} textAnchor="middle" style={{ fontSize: 10 }}>predicted home-win probability</text>
      </svg>
      {h && (
        <div className="tooltip" style={{ left: Math.min(x(h.predicted) + 14, width - 170), top: y(h.actual) - 10 }}>
          <div className="t-title">{h.bin * 10}–{h.bin * 10 + 10}% bin</div>
          <div className="t-row">Predicted: <b>{(h.predicted * 100).toFixed(1)}%</b></div>
          <div className="t-row">Actual: <b>{(h.actual * 100).toFixed(1)}%</b></div>
          <div className="t-row">{h.games} games</div>
        </div>
      )}
    </div>
  );
}
