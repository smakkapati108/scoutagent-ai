import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api, pct, type Meta, type Report } from '../api';
import { Card, ErrorBox, TeamSelect, useAsync } from '../components/common';

type AgentId = 'data_analyst' | 'tactical_scout' | 'game_planner';
const AGENTS: { id: AgentId; name: string; role: string }[] = [
  { id: 'data_analyst', name: 'Data Analyst', role: 'Season profiles, form, head-to-head, verified statistical edges' },
  { id: 'tactical_scout', name: 'Tactical Scout', role: 'Opponent tendencies, situational splits, four-factor matchup' },
  { id: 'game_planner', name: 'Game Planner', role: 'Synthesizes both reports and runs win-probability scenarios' },
];

interface StreamEvent { type: string; agent: string; at: number; data: Record<string, unknown> }
type Status = 'idle' | 'running' | 'done';

export function ScoutingPage({ meta }: { meta: Meta }) {
  const [home, setHome] = useState(meta.teams[2]?.abbr ?? meta.teams[0].abbr);
  const [away, setAway] = useState(meta.teams[7]?.abbr ?? meta.teams[1].abbr);
  const [focus, setFocus] = useState<'home' | 'away'>('home');
  const [events, setEvents] = useState<StreamEvent[]>([]);
  const [statuses, setStatuses] = useState<Record<string, Status>>({});
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string>();
  const [report, setReport] = useState<Report & { usage?: Record<string, Record<string, number>> }>();
  const [startedAt, setStartedAt] = useState<number>();
  const [now, setNow] = useState(Date.now());
  const [reportsVersion, setReportsVersion] = useState(0);
  const source = useRef<EventSource | null>(null);
  const status = useAsync(api.agentStatus, []);
  const reports = useAsync(api.reports, [reportsVersion]);

  useEffect(() => () => source.current?.close(), []);
  useEffect(() => {
    if (!running) return;
    const t = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(t);
  }, [running]);

  const start = () => {
    source.current?.close();
    setEvents([]);
    setStatuses({});
    setReport(undefined);
    setError(undefined);
    setRunning(true);
    setStartedAt(Date.now());
    const params = new URLSearchParams({ home, away, focus: focus === 'home' ? home : away });
    const es = new EventSource(`/api/scouting/stream?${params}`);
    source.current = es;
    const handle = (e: MessageEvent) => {
      const ev: StreamEvent = JSON.parse(e.data);
      setEvents((prev) => [...prev, ev]);
      if (ev.type === 'agent_started') setStatuses((s) => ({ ...s, [ev.agent]: 'running' }));
      if (ev.type === 'agent_completed') setStatuses((s) => ({ ...s, [ev.agent]: 'done' }));
      if (ev.type === 'report_completed') {
        setReport(ev.data as unknown as Report);
        finish();
      }
      if (ev.type === 'error') {
        setError(String(ev.data.message));
        finish();
      }
    };
    const finish = () => {
      es.close();
      setRunning(false);
      setNow(Date.now());
      setReportsVersion((v) => v + 1);
    };
    ['run_started', 'agent_started', 'tool_call', 'tool_result', 'agent_thought', 'agent_completed', 'agent_output',
      'report_completed', 'error'].forEach((t) => es.addEventListener(t, handle as EventListener));
    es.onerror = () => {
      if (es.readyState !== EventSource.CLOSED) {
        setError('Lost connection to the backend stream.');
        finish();
      }
    };
  };

  const openSaved = async (id: number) => {
    setError(undefined);
    setEvents([]);
    setStatuses({});
    setStartedAt(undefined);
    try { setReport(await api.report(id)); } catch (e) { setError((e as Error).message); }
  };

  const elapsed = startedAt ? ((running ? now : (report ? startedAt + report.duration_ms : now)) - startedAt) / 1000 : 0;
  const toolCalls = events.filter((e) => e.type === 'tool_call').length;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Scouting agents</h1>
          <p>Three Claude agents query the game-log database with tools and write a tactical game plan.</p>
        </div>
        {status.data && (
          <span className="pill">{status.data.model}{!status.data.credentials_in_environment && ' · no API key in env'}</span>
        )}
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="controls" style={{ gap: 18, alignItems: 'flex-end' }}>
          <TeamSelect label="Home" teams={meta.teams} value={home} onChange={setHome} exclude={away} />
          <TeamSelect label="Away" teams={meta.teams} value={away} onChange={setAway} exclude={home} />
          <label className="field">Game-plan for
            <select value={focus} onChange={(e) => setFocus(e.target.value as 'home' | 'away')}>
              <option value="home">{home} (home)</option>
              <option value="away">{away} (away)</option>
            </select>
          </label>
          <button className="primary" onClick={start} disabled={running}>{running ? 'Agents working…' : 'Generate game plan'}</button>
          {(running || startedAt) && (
            <span className="muted mono">{elapsed.toFixed(1)}s · {toolCalls} tool calls</span>
          )}
        </div>
      </div>

      <ErrorBox message={error} />

      {(events.length > 0 || running) && (
        <div className="agents" style={{ margin: '16px 0' }}>
          {AGENTS.map((a) => {
            const st = statuses[a.id] ?? 'idle';
            const mine = events.filter((e) => e.agent === a.id && (e.type === 'tool_call' || e.type === 'agent_thought'));
            return (
              <div key={a.id} className="card agent-col">
                <div className="agent-head">
                  <h2><span className={`status-dot ${st}`} />{a.name}</h2>
                  <span className="muted" style={{ fontSize: 12 }}>{st === 'done' ? 'done' : st === 'running' ? 'working' : 'waiting'}</span>
                </div>
                <div className="muted" style={{ fontSize: 12 }}>{a.role}</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 320, overflowY: 'auto' }}>
                  {mine.map((e, i) => e.type === 'tool_call' ? (
                    <div key={i} className="event">
                      <div className="ev-tool">{String(e.data.tool)}</div>
                      <div className="ev-input" title={JSON.stringify(e.data.input)}>{JSON.stringify(e.data.input)}</div>
                    </div>
                  ) : (
                    <div key={i} className="event thought">{String(e.data.text).slice(0, 240)}</div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {report && <ReportView report={report} />}

      <Card title="Saved reports" sub="stored in PostgreSQL">
        {!reports.data?.length ? <div className="empty">No reports yet.</div> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Created</th><th>Game</th><th>Plan for</th><th className="num">Win prob.</th><th className="num">Time</th><th className="num">Tool calls</th><th /></tr></thead>
              <tbody>
                {reports.data.map((r) => (
                  <tr key={r.id}>
                    <td className="muted">{new Date(r.created_at).toLocaleString()}</td>
                    <td>{r.away} @ {r.home}</td>
                    <td><b>{r.focus}</b></td>
                    <td className="num">{pct(r.win_probability)}</td>
                    <td className="num">{(r.duration_ms / 1000).toFixed(1)}s</td>
                    <td className="num">{r.tool_calls}</td>
                    <td><button onClick={() => openSaved(r.id)}>Open</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

function ReportView({ report }: { report: Report }) {
  const [tab, setTab] = useState<'plan' | 'analyst' | 'scout'>('plan');
  const body = tab === 'plan' ? report.game_plan : tab === 'analyst' ? report.analyst_notes : report.scout_notes;
  return (
    <Card className="report" title={`${report.away} @ ${report.home} — plan for ${report.focus}`}
      sub={`${(report.duration_ms / 1000).toFixed(1)}s · ${report.tool_calls} tool calls · ${report.model}`}>
      <div className="tabs">
        <button className={`tab ${tab === 'plan' ? 'active' : ''}`} onClick={() => setTab('plan')}>Game plan</button>
        <button className={`tab ${tab === 'analyst' ? 'active' : ''}`} onClick={() => setTab('analyst')}>Data Analyst</button>
        <button className={`tab ${tab === 'scout' ? 'active' : ''}`} onClick={() => setTab('scout')}>Tactical Scout</button>
      </div>
      <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{body}</ReactMarkdown></div>
    </Card>
  );
}
