import { useState } from 'react';
import { api, featureLabel, pct, signed, type Meta } from '../api';
import { HBars, ProbabilitySplit } from '../charts/Bars';
import { LineChart } from '../charts/LineChart';
import { Card, ErrorBox, Loading, TeamSelect, useAsync } from '../components/common';

export function MatchupPage({ meta }: { meta: Meta }) {
  const [home, setHome] = useState(meta.teams[2]?.abbr ?? meta.teams[0].abbr);
  const [away, setAway] = useState(meta.teams[7]?.abbr ?? meta.teams[1].abbr);
  const [homeRest, setHomeRest] = useState(1);
  const [awayRest, setAwayRest] = useState(1);
  const [homeNetDelta, setHomeNetDelta] = useState(0);

  const pred = useAsync(() => api.predict({ home, away, homeRest, awayRest, homeNetDelta, awayNetDelta: 0 }),
    [home, away, homeRest, awayRest, homeNetDelta]);
  const matchup = useAsync(() => api.matchup(home, away), [home, away]);
  const trends = useAsync(() => Promise.all([api.trend(home), api.trend(away)]), [home, away]);
  const p = pred.data;

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Matchup predictor</h1>
          <p>Win probability from the trained model, with what-if scenarios.</p>
        </div>
      </div>
      <div className="card" style={{ marginBottom: 16 }}>
        <div className="controls" style={{ gap: 18, alignItems: 'flex-end' }}>
          <TeamSelect label="Home" teams={meta.teams} value={home} onChange={setHome} exclude={away} />
          <TeamSelect label="Away" teams={meta.teams} value={away} onChange={setAway} exclude={home} />
          <label className="field">Home rest: {homeRest === 3 ? '3+' : homeRest} {homeRest === 0 && '(B2B)'}
            <input type="range" min={0} max={3} value={homeRest} onChange={(e) => setHomeRest(+e.target.value)} />
          </label>
          <label className="field">Away rest: {awayRest === 3 ? '3+' : awayRest} {awayRest === 0 && '(B2B)'}
            <input type="range" min={0} max={3} value={awayRest} onChange={(e) => setAwayRest(+e.target.value)} />
          </label>
          <label className="field">Game-plan effect on {home}: {signed(homeNetDelta)} net rtg
            <input type="range" min={-8} max={8} step={0.5} value={homeNetDelta} onChange={(e) => setHomeNetDelta(+e.target.value)} />
          </label>
        </div>
      </div>
      <ErrorBox message={pred.error ?? matchup.error} />
      {!p ? <Loading /> : (
        <div className="grid">
          <div className="grid cols-2">
            <Card title="Win probability" sub={`model test accuracy ${pct(p.model_test_accuracy)}`}>
              <ProbabilitySplit home={p.home} away={p.away} pHome={p.home_win_probability} />
              <table style={{ marginTop: 16 }}>
                <thead><tr><th /><th className="num">{p.home}</th><th className="num">{p.away}</th></tr></thead>
                <tbody>
                  <tr><td>Elo</td><td className="num">{p.home_state.elo.toFixed(0)}</td><td className="num">{p.away_state.elo.toFixed(0)}</td></tr>
                  <tr><td>Win %</td><td className="num">{pct(p.home_state.win_pct)}</td><td className="num">{pct(p.away_state.win_pct)}</td></tr>
                  <tr><td>Season net rating</td><td className="num">{signed(p.home_state.season_net_rating)}</td><td className="num">{signed(p.away_state.season_net_rating)}</td></tr>
                  <tr><td>Last-10 net rating</td><td className="num">{signed(p.home_state.last10_net_rating)}</td><td className="num">{signed(p.away_state.last10_net_rating)}</td></tr>
                </tbody>
              </table>
            </Card>
            <Card title="What drives the prediction" sub="contribution to log-odds vs. an average game">
              <HBars diverging posLabel={`favors ${p.home}`} negLabel={`favors ${p.away}`}
                data={p.factors.map((f) => ({
                  label: featureLabel(f.feature), value: f.log_odds_contribution,
                  display: signed(f.log_odds_contribution, 2), detail: `Feature value: ${f.value}`,
                }))} />
            </Card>
          </div>

          <Card title="Form comparison" sub="10-game rolling net rating, current season">
            <div className="legend" style={{ marginBottom: 8 }}>
              <span><span className="swatch" style={{ background: 'var(--series-1)' }} />{home}</span>
              <span><span className="swatch" style={{ background: 'var(--series-2)' }} />{away}</span>
            </div>
            {!trends.data ? <Loading /> : (
              <LineChart xLabel="Game" yLabel="Net rating"
                series={[
                  { name: home, color: 'var(--series-1)', points: trends.data[0].map((g) => ({ x: g.game_number, y: g.rolling10_net_rating })) },
                  { name: away, color: 'var(--series-2)', points: trends.data[1].map((g) => ({ x: g.game_number, y: g.rolling10_net_rating })) },
                ]} />
            )}
          </Card>

          {matchup.data && (
            <Card title="Four-factor matchup" sub="offense rank vs. opposing defense rank · positive edge favors the offense">
              <HBars diverging posLabel="offense edge" negLabel="defense edge" labelWidth={300}
                data={matchup.data.four_factor_edges.map((e) => ({
                  label: e.factor, value: e.edge_for_offense, display: signed(e.edge_for_offense, 0),
                  detail: `${e.offense_team} offense #${e.offense_rank} (${e.offense_value}) vs ${e.defense_team} defense #${e.defense_rank} (${e.defense_value})`,
                }))} />
            </Card>
          )}
        </div>
      )}
    </>
  );
}
