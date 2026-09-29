import { api, pct, signed, type Meta } from '../api';
import { CalibrationChart } from '../charts/CalibrationChart';
import { HBars } from '../charts/Bars';
import { Card, ErrorBox, Loading, StatTile, useAsync } from '../components/common';
import { Link } from 'react-router-dom';

export function Dashboard({ meta }: { meta: Meta }) {
  const metrics = useAsync(api.modelMetrics, []);
  const standings = useAsync(() => api.standings(), []);
  const m = metrics.data;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>League overview</h1>
          <p>Season {meta.current_season} · {meta.team_game_logs.toLocaleString()} team game logs across {meta.seasons.length} seasons</p>
        </div>
      </div>
      <ErrorBox message={metrics.error ?? standings.error} />
      {!m ? <Loading /> : (
        <div className="grid" style={{ gap: 16 }}>
          <div className="grid cols-4">
            <StatTile label="Out-of-sample accuracy" value={pct(m.test.accuracy)}
              note={`${m.test.games.toLocaleString()} held-out games (${m.test_seasons})`} />
            <StatTile label="Lift vs. home-team baseline" value={`${signed(m.test.relative_lift_vs_home_baseline_pct)}%`}
              note={`baseline ${pct(m.test.baseline_accuracy.always_pick_home)}`} />
            <StatTile label="Log loss (test)" value={m.test.log_loss.toFixed(3)} note={`Brier ${m.test.brier_score.toFixed(3)} · lower is better`} />
            <StatTile label="Training games" value={m.train_games.toLocaleString()} note={`seasons ${m.train_seasons}`} />
          </div>

          <div className="grid cols-2">
            <Card title="Win-probability model vs. baselines" sub="accuracy on held-out games">
              <HBars
                domain={[0.4, Math.max(0.75, m.test.accuracy + 0.05)]}
                data={[
                  { label: 'ScoutAgent model', value: m.test.accuracy, display: pct(m.test.accuracy), highlight: true,
                    detail: `Logistic regression on 9 pre-game features · log loss ${m.test.log_loss}` },
                  { label: 'Elo only', value: m.test.baseline_accuracy.elo_only, display: pct(m.test.baseline_accuracy.elo_only), highlight: false,
                    detail: 'Pick the higher Elo rating (with home-court adjustment)' },
                  { label: 'Better record', value: m.test.baseline_accuracy.better_record, display: pct(m.test.baseline_accuracy.better_record), highlight: false,
                    detail: 'Pick the team with the better record so far' },
                  { label: 'Always home team', value: m.test.baseline_accuracy.always_pick_home, display: pct(m.test.baseline_accuracy.always_pick_home), highlight: false,
                    detail: 'Always pick the home team' },
                ]}
              />
              <div className="muted" style={{ fontSize: 12, marginTop: 10 }}>
                Per season: {m.per_season_test_accuracy.map((s) => `${s.season} ${pct(s.accuracy)}`).join(' · ')}
              </div>
            </Card>
            <Card title="Calibration" sub="dot size = games in bin">
              <CalibrationChart bins={m.calibration} height={280} />
            </Card>
          </div>

          <div className="grid cols-3">
            <Card title="Standings" sub={`season ${meta.current_season}`} className="span-2">
              {!standings.data ? <Loading /> : (
                <div className="table-wrap" style={{ maxHeight: 440, overflowY: 'auto' }}>
                  <table>
                    <thead>
                      <tr><th>#</th><th>Team</th><th>Conf</th><th className="num">W-L</th><th className="num">Net Rtg</th>
                        <th className="num">Off</th><th className="num">Def</th><th className="num">Pace</th><th className="num">Home</th><th className="num">Away</th></tr>
                    </thead>
                    <tbody>
                      {standings.data.map((t, i) => (
                        <tr key={t.team}>
                          <td className="muted">{i + 1}</td>
                          <td><Link to={`/teams/${t.team}`}><b>{t.team}</b></Link> <span className="muted">{t.team_name}</span></td>
                          <td className="muted">{t.conference}</td>
                          <td className="num">{t.wins}-{t.losses}</td>
                          <td className="num">{signed(t.net_rating)}</td>
                          <td className="num">{t.off_rating}</td>
                          <td className="num">{t.def_rating}</td>
                          <td className="num">{t.pace}</td>
                          <td className="num muted">{t.home_record}</td>
                          <td className="num muted">{t.away_record}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>
            <Card title="Recent test predictions" sub="latest held-out games">
              <div className="table-wrap" style={{ maxHeight: 440, overflowY: 'auto' }}>
                <table>
                  <thead><tr><th>Game</th><th className="num">P(home)</th><th className="num">Final</th><th /></tr></thead>
                  <tbody>
                    {m.recent_test_predictions.map((p) => (
                      <tr key={p.gameId}>
                        <td>{p.away} @ <b>{p.home}</b><div className="muted" style={{ fontSize: 11 }}>{p.date}</div></td>
                        <td className="num">{pct(p.homeWinProbability, 0)}</td>
                        <td className="num">{p.awayScore}-{p.homeScore}</td>
                        <td className={p.correct ? 'win' : 'loss'}>{p.correct ? '✓' : '✗'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>
        </div>
      )}
    </>
  );
}
