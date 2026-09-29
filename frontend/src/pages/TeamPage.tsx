import { useNavigate, useParams } from 'react-router-dom';
import { api, pct, signed, type Meta } from '../api';
import { LineChart } from '../charts/LineChart';
import { Card, ErrorBox, Loading, StatTile, TeamSelect, useAsync } from '../components/common';

const rankNote = (r?: number) => (r ? `#${r} in league` : '');

export function TeamPage({ meta }: { meta: Meta }) {
  const { abbr = meta.teams[0].abbr } = useParams();
  const navigate = useNavigate();
  const summary = useAsync(() => api.summary(abbr), [abbr]);
  const trend = useAsync(() => api.trend(abbr), [abbr]);
  const splits = useAsync(() => api.splits(abbr), [abbr]);
  const games = useAsync(() => api.games(abbr, 12), [abbr]);
  const s = summary.data;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>{s?.team_name ?? abbr}</h1>
          <p>{s ? `${s.wins}-${s.losses} · ${s.conference} · season ${s.season}` : ' '}</p>
        </div>
        <TeamSelect teams={meta.teams} value={abbr} onChange={(t) => navigate(`/teams/${t}`)} />
      </div>
      <ErrorBox message={summary.error ?? trend.error} />
      {!s ? <Loading /> : (
        <div className="grid">
          <div className="grid cols-4">
            <StatTile label="Net rating" value={signed(s.net_rating)} note={rankNote(s.league_ranks.net_rating)} />
            <StatTile label="Offensive rating" value={s.off_rating} note={rankNote(s.league_ranks.off_rating)} />
            <StatTile label="Defensive rating" value={s.def_rating} note={rankNote(s.league_ranks.def_rating)} />
            <StatTile label="Pace" value={s.pace} note={rankNote(s.league_ranks.pace)} />
          </div>

          <Card title="Form: 10-game rolling net rating" sub="dots = single-game net rating · hover for details">
            {trend.data ? (
              <LineChart
                xLabel="Game"
                yLabel="Net rating (pts / 100 poss.)"
                series={[{
                  name: abbr, color: 'var(--series-1)',
                  points: trend.data.map((g) => ({
                    x: g.game_number, y: g.rolling10_net_rating,
                    label: `${g.result} ${g.points}-${g.opp_points} vs ${g.opponent}`,
                  })),
                }]}
                rawDots={trend.data.map((g) => ({ x: g.game_number, y: g.net_rating }))}
              />
            ) : <Loading />}
          </Card>

          <div className="grid cols-2">
            <Card title="Four factors" sub="league rank in parentheses">
              <table>
                <thead><tr><th>Factor</th><th className="num">Offense</th><th className="num">Defense (opp.)</th></tr></thead>
                <tbody>
                  {[
                    ['Effective FG%', pct(s.efg_pct), 'efg_pct', pct(s.opp_efg_pct), 'opp_efg_pct'],
                    ['Turnover rate', pct(s.tov_pct), 'tov_pct', pct(s.opp_tov_pct), 'opp_tov_pct'],
                    ['Rebounding', `${pct(s.oreb_pct)} OREB`, 'oreb_pct', `${pct(s.dreb_pct)} DREB`, 'dreb_pct'],
                    ['Free-throw rate', s.ft_rate.toFixed(3), 'ft_rate', s.opp_ft_rate.toFixed(3), 'opp_ft_rate'],
                  ].map(([label, off, offKey, def, defKey]) => (
                    <tr key={label}>
                      <td>{label}</td>
                      <td className="num">{off} <span className="muted">({s.league_ranks[offKey]})</span></td>
                      <td className="num">{def} <span className="muted">({s.league_ranks[defKey]})</span></td>
                    </tr>
                  ))}
                  <tr><td>3PT rate / 3PT%</td><td className="num">{pct(s.three_rate)} / {pct(s.three_pct)}</td><td /></tr>
                </tbody>
              </table>
            </Card>
            <Card title="Situational splits">
              {!splits.data ? <Loading /> : (
                <div className="table-wrap">
                  <table>
                    <thead><tr><th>Split</th><th className="num">GP</th><th className="num">Record</th><th className="num">Margin</th><th className="num">Off</th><th className="num">Def</th></tr></thead>
                    <tbody>
                      {splits.data.map((r) => (
                        <tr key={r.split}>
                          <td>{r.split.replaceAll('_', ' ')}</td>
                          <td className="num">{r.games}</td>
                          <td className="num">{r.record}</td>
                          <td className="num">{signed(r.avg_margin)}</td>
                          <td className="num">{r.off_rating}</td>
                          <td className="num">{r.def_rating}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>
          </div>

          <Card title="Recent games">
            {!games.data ? <Loading /> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Date</th><th>Opp</th><th /><th className="num">Score</th><th className="num">Poss</th><th className="num">3PM-A</th><th className="num">TOV</th><th className="num">OREB</th><th className="num">Rest</th></tr></thead>
                  <tbody>
                    {games.data.map((g) => (
                      <tr key={g.game_date}>
                        <td className="muted">{g.game_date}</td>
                        <td>{g.venue === 'home' ? 'vs' : '@'} {g.opponent}</td>
                        <td className={g.result === 'W' ? 'win' : 'loss'}>{g.result}</td>
                        <td className="num">{g.points}-{g.opp_points}</td>
                        <td className="num">{g.possessions}</td>
                        <td className="num">{g.fg3m}-{g.fg3a}</td>
                        <td className="num">{g.tov}</td>
                        <td className="num">{g.oreb}</td>
                        <td className="num">{g.rest_days === 0 ? <span className="pill">B2B</span> : g.rest_days}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </div>
      )}
    </>
  );
}
