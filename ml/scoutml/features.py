"""
Pre-game feature engineering, a line-for-line port of the backend's FeatureEngine.java.

Games are replayed in date order. Each game's features are computed from the teams' state *before* the game,
then the state is updated with the result, so no feature can see its own outcome (no leakage).
"""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

FEATURES = [
    "elo_diff", "season_net_rating_diff", "last10_net_rating_diff", "season_win_pct_diff",
    "rest_days_diff", "home_back_to_back", "away_back_to_back", "season_efg_diff", "season_tov_pct_diff",
]

ELO_HOME_ADV = 70.0
ELO_K = 20.0


@dataclass
class TeamState:
    elo: float = 1500.0
    season: int = -1
    games: int = 0
    wins: int = 0
    pts: float = 0.0
    opp_pts: float = 0.0
    poss: float = 0.0
    fgm: float = 0.0
    fg3m: float = 0.0
    fga: float = 0.0
    tov: float = 0.0
    last10: deque = field(default_factory=lambda: deque(maxlen=10))
    last_played: pd.Timestamp | None = None
    prior_net: float = 0.0

    def new_season(self, season: int) -> None:
        # Early-season net rating shrinks toward half of last season's, and Elo regresses toward 1500.
        self.prior_net = 0.0 if self.poss == 0 else 0.5 * 100 * (self.pts - self.opp_pts) / self.poss
        self.season = season
        self.elo = 0.75 * self.elo + 0.25 * 1500
        self.games = self.wins = 0
        self.pts = self.opp_pts = self.poss = self.fgm = self.fg3m = self.fga = self.tov = 0.0
        self.last10.clear()
        self.last_played = None

    def net_rating(self) -> float:
        raw = 0.0 if self.poss == 0 else 100 * (self.pts - self.opp_pts) / self.poss
        return (raw * self.games + self.prior_net * 10) / (self.games + 10.0)

    def last10_net(self) -> float:
        if not self.last10:
            return 0.0
        n = len(self.last10)
        return (sum(self.last10) / n) * n / (n + 3.0)

    def win_pct(self) -> float:
        return (self.wins + 2.5) / (self.games + 5.0)

    def raw_win_pct(self) -> float:
        return 0.5 if self.games == 0 else self.wins / self.games

    def efg(self) -> float:
        return 0.53 if self.fga == 0 else (self.fgm + 0.5 * self.fg3m) / self.fga

    def tov_pct(self) -> float:
        return 0.13 if self.poss == 0 else self.tov / self.poss

    def rest_days(self, date: pd.Timestamp) -> int:
        if self.last_played is None:
            return 3
        return int(min(3, (date - self.last_played).days - 1))

    def apply(self, pts: int, opp: int, poss: float, fgm: int, fg3m: int, fga: int, tov: int,
              date: pd.Timestamp) -> None:
        self.games += 1
        self.wins += int(pts > opp)
        self.pts += pts
        self.opp_pts += opp
        self.poss += poss
        self.fgm += fgm
        self.fg3m += fg3m
        self.fga += fga
        self.tov += tov
        self.last10.append(100.0 * (pts - opp) / poss)
        self.last_played = date


def _features(h: TeamState, a: TeamState, home_rest: int, away_rest: int) -> list[float]:
    return [
        (h.elo - a.elo) / 100.0,
        h.net_rating() - a.net_rating(),
        h.last10_net() - a.last10_net(),
        h.win_pct() - a.win_pct(),
        float(min(home_rest, 3) - min(away_rest, 3)),
        float(home_rest == 0),
        float(away_rest == 0),
        (h.efg() - a.efg()) * 100,
        (h.tov_pct() - a.tov_pct()) * 100,
    ]


def _update_elo(h: TeamState, a: TeamState, margin: int) -> None:
    expected_home = 1 / (1 + 10 ** (-(h.elo + ELO_HOME_ADV - a.elo) / 400))
    actual = 1.0 if margin > 0 else 0.0
    winner_elo_diff = h.elo + ELO_HOME_ADV - a.elo if margin > 0 else a.elo - h.elo - ELO_HOME_ADV
    mov_mult = math.log(abs(margin) + 1) * 2.2 / (winner_elo_diff * 0.001 + 2.2)
    delta = ELO_K * mov_mult * (actual - expected_home)
    h.elo += delta
    a.elo -= delta


def build_features(games: pd.DataFrame) -> pd.DataFrame:
    """Returns one row per game: identifiers, the 9 pre-game features, baseline inputs and the label."""
    games = games.sort_values(["game_date", "game_id"], kind="stable")
    states: dict[int, TeamState] = {}

    def state(team_id: int, season: int) -> TeamState:
        s = states.setdefault(team_id, TeamState())
        if s.season != season:
            s.new_season(season)
        return s

    rows = []
    for g in games.itertuples(index=False):
        h = state(g.home_team_id, g.season)
        a = state(g.away_team_id, g.season)
        home_rest, away_rest = h.rest_days(g.game_date), a.rest_days(g.game_date)
        rows.append([g.game_id, g.season, g.game_date, g.home_team_id, g.away_team_id,
                     *_features(h, a, home_rest, away_rest),
                     h.raw_win_pct(), a.raw_win_pct(), int(g.home_score > g.away_score)])

        _update_elo(h, a, g.home_score - g.away_score)
        h.apply(g.home_score, g.away_score, g.possessions, g.home_fgm, g.home_fg3m, g.home_fga, g.home_tov, g.game_date)
        a.apply(g.away_score, g.home_score, g.possessions, g.away_fgm, g.away_fg3m, g.away_fga, g.away_tov, g.game_date)

    cols = ["game_id", "season", "game_date", "home_team_id", "away_team_id", *FEATURES,
            "home_win_pct_before", "away_win_pct_before", "home_won"]
    out = pd.DataFrame(rows, columns=cols)
    out[FEATURES] = out[FEATURES].astype(np.float64)
    return out
