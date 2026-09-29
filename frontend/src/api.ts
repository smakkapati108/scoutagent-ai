export interface Team { id: number; abbr: string; name: string; conference: string }

export interface Meta { current_season: number; seasons: number[]; team_game_logs: number; teams: Team[] }

export interface TeamSeason {
  team: string; team_name: string; conference: string; season: number; games: number; wins: number; losses: number;
  win_pct: number; home_record: string; away_record: string; ppg: number; opp_ppg: number;
  off_rating: number; def_rating: number; net_rating: number; pace: number;
  efg_pct: number; tov_pct: number; oreb_pct: number; ft_rate: number; three_rate: number; three_pct: number;
  opp_efg_pct: number; opp_tov_pct: number; dreb_pct: number; opp_ft_rate: number;
  league_ranks: Record<string, number>;
}

export interface GameLog {
  game_date: string; season: number; opponent: string; venue: 'home' | 'away'; result: 'W' | 'L';
  points: number; opp_points: number; margin: number; possessions: number; rest_days: number;
  fg3m: number; fg3a: number; tov: number; oreb: number; ast: number;
}

export interface TrendPoint {
  game_number: number; game_date: string; opponent: string; result: 'W' | 'L'; points: number; opp_points: number;
  net_rating: number; rolling10_net_rating: number; cumulative_wins: number;
}

export interface Split {
  split: string; games: number; record: string; win_pct: number; avg_margin: number;
  off_rating: number; def_rating: number; pace: number; tov_per_game: number; three_pct: number;
}

export interface Edge {
  factor: string; offense_team: string; offense_value: number; offense_rank: number;
  defense_team: string; defense_value: number; defense_rank: number; edge_for_offense: number;
}
export interface Matchup { season: number; team: TeamSeason; opponent: TeamSeason; four_factor_edges: Edge[] }

export interface Factor { feature: string; value: number; log_odds_contribution: number }
export interface Prediction {
  home: string; away: string; home_win_probability: number; away_win_probability: number;
  home_rest_days: number; away_rest_days: number; factors: Factor[]; model_test_accuracy: number;
  home_state: Record<string, number>; away_state: Record<string, number>;
}

export interface EvalMetrics {
  games: number; accuracy: number; log_loss: number; brier_score: number;
  baseline_accuracy: { always_pick_home: number; better_record: number; elo_only: number };
  relative_lift_vs_home_baseline_pct: number; relative_lift_vs_record_baseline_pct: number;
}
export interface ModelMetrics {
  trained_at: string; train_seasons: string; test_seasons: string; train_games: number; test_games: number;
  test: EvalMetrics; train: EvalMetrics;
  per_season_test_accuracy: { season: number; games: number; accuracy: number }[];
  calibration: { bin: number; predicted: number; actual: number; games: number }[];
  coefficients: { feature: string; weight: number }[];
  recent_test_predictions: {
    gameId: number; date: string; home: string; away: string; homeWinProbability: number;
    homeScore: number; awayScore: number; correct: boolean;
  }[];
}

export interface ReportSummary {
  id: number; created_at: string; home: string; away: string; focus: string; win_probability: number;
  duration_ms: number; tool_calls: number; model: string;
}
export interface Report extends ReportSummary { analyst_notes: string; scout_notes: string; game_plan: string }

async function get<T>(path: string): Promise<T> {
  const res = await fetch(path);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error ?? `${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<T>;
}

const q = (params: Record<string, string | number | undefined>) =>
  new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)])).toString();

export const api = {
  meta: () => get<Meta>('/api/meta'),
  standings: (season?: number) => get<TeamSeason[]>(`/api/standings?${q({ season })}`),
  summary: (team: string, season?: number) => get<TeamSeason>(`/api/teams/${team}/summary?${q({ season })}`),
  games: (team: string, limit = 10) => get<GameLog[]>(`/api/teams/${team}/games?limit=${limit}`),
  trend: (team: string, season?: number) => get<TrendPoint[]>(`/api/teams/${team}/trend?${q({ season })}`),
  splits: (team: string, season?: number) => get<Split[]>(`/api/teams/${team}/splits?${q({ season })}`),
  matchup: (team: string, opponent: string) => get<Matchup>(`/api/matchup?${q({ team, opponent })}`),
  predict: (p: { home: string; away: string; homeRest: number; awayRest: number; homeNetDelta: number; awayNetDelta: number }) =>
    get<Prediction>(`/api/predict?${q(p)}`),
  modelMetrics: () => get<ModelMetrics>('/api/model/metrics'),
  agentStatus: () => get<{ model: string; credentials_in_environment: boolean }>('/api/scouting/status'),
  reports: () => get<ReportSummary[]>('/api/scouting/reports'),
  report: (id: number) => get<Report>(`/api/scouting/reports/${id}`),
};

export const pct = (v: number, digits = 1) => `${(v * 100).toFixed(digits)}%`;
export const signed = (v: number, digits = 1) => `${v > 0 ? '+' : ''}${v.toFixed(digits)}`;
export const featureLabel = (f: string) =>
  ({
    elo_diff: 'Elo rating gap',
    season_net_rating_diff: 'Season net rating gap',
    last10_net_rating_diff: 'Last-10 net rating gap',
    season_win_pct_diff: 'Win % gap',
    rest_days_diff: 'Rest advantage',
    home_back_to_back: 'Home on back-to-back',
    away_back_to_back: 'Away on back-to-back',
    season_efg_diff: 'Shooting (eFG%) gap',
    season_tov_pct_diff: 'Turnover rate gap',
  })[f] ?? f;
