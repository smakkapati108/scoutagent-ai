import { useEffect, useState, type ReactNode } from 'react';
import type { Team } from '../api';

/** Fetches on mount and whenever deps change; ignores stale responses. */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [state, setState] = useState<{ data?: T; error?: string; loading: boolean }>({ loading: true });
  useEffect(() => {
    let live = true;
    setState((s) => ({ ...s, loading: true, error: undefined }));
    fn().then(
      (data) => live && setState({ data, loading: false }),
      (e: Error) => live && setState({ error: e.message, loading: false }),
    );
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return state;
}

export function Card({ title, sub, children, className = '' }: { title?: ReactNode; sub?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || sub) && (
        <div className="card-head">
          {title && <h2>{title}</h2>}
          {sub && <span className="sub">{sub}</span>}
        </div>
      )}
      {children}
    </section>
  );
}

export function StatTile({ label, value, note }: { label: string; value: ReactNode; note?: ReactNode }) {
  return (
    <div className="card tile">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {note && <div className="note">{note}</div>}
    </div>
  );
}

export function TeamSelect({ teams, value, onChange, label, exclude }: {
  teams: Team[]; value: string; onChange: (v: string) => void; label?: string; exclude?: string;
}) {
  const select = (
    <select value={value} onChange={(e) => onChange(e.target.value)} aria-label={label ?? 'Team'}>
      {teams.filter((t) => t.abbr !== exclude).map((t) => (
        <option key={t.abbr} value={t.abbr}>{t.abbr} · {t.name}</option>
      ))}
    </select>
  );
  return label ? <label className="field">{label}{select}</label> : select;
}

export function ErrorBox({ message }: { message?: string }) {
  return message ? <div className="error">{message}</div> : null;
}

export function Loading() {
  return <div className="empty">Loading…</div>;
}
