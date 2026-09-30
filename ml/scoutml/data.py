"""Loads completed games from the ScoutAgent PostgreSQL database into a pandas DataFrame."""
from __future__ import annotations

import os

import pandas as pd
from sqlalchemy import create_engine

DEFAULT_DB_URL = "postgresql+psycopg://scout:scout@localhost:5433/scoutagent"

GAMES_SQL = """
SELECT g.id AS game_id, g.season, g.game_date, g.home_team_id, g.away_team_id,
       g.home_score, g.away_score, h.possessions,
       h.fgm AS home_fgm, h.fg3m AS home_fg3m, h.fga AS home_fga, h.tov AS home_tov,
       a.fgm AS away_fgm, a.fg3m AS away_fg3m, a.fga AS away_fga, a.tov AS away_tov
FROM games g
JOIN team_game_logs h ON h.game_id = g.id AND h.team_id = g.home_team_id
JOIN team_game_logs a ON a.game_id = g.id AND a.team_id = g.away_team_id
ORDER BY g.game_date, g.id
"""


def load_games(db_url: str | None = None) -> pd.DataFrame:
    """One row per game, in chronological order (the order the feature replay requires)."""
    engine = create_engine(db_url or os.environ.get("SCOUT_DB_URL", DEFAULT_DB_URL))
    with engine.connect() as conn:
        df = pd.read_sql(GAMES_SQL, conn, parse_dates=["game_date"])
    if df.empty:
        raise RuntimeError("No games found; start the backend once so it seeds the database.")
    return df
