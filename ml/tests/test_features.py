"""Feature-pipeline invariants on a small hand-built schedule (no database needed)."""
import numpy as np
import pandas as pd

from scoutml.evaluate import walk_forward
from scoutml.features import FEATURES, build_features


def make_games(n_seasons=4, seed=0):
    """A tiny league: 4 teams, round-robin twice per season, with random scores."""
    rng = np.random.default_rng(seed)
    rows, gid = [], 1
    for season in range(2020, 2020 + n_seasons):
        day = pd.Timestamp(f"{season - 1}-10-20")
        for rep in range(8):
            for home, away in [(1, 2), (3, 4), (1, 3), (2, 4), (1, 4), (2, 3)]:
                if rep % 2:
                    home, away = away, home
                hs, as_ = rng.integers(90, 130), rng.integers(90, 130)
                if hs == as_:
                    hs += 1
                rows.append(dict(game_id=gid, season=season, game_date=day, home_team_id=home, away_team_id=away,
                                 home_score=int(hs), away_score=int(as_), possessions=100.0,
                                 home_fgm=40, home_fg3m=12, home_fga=88, home_tov=13,
                                 away_fgm=39, away_fg3m=11, away_fga=87, away_tov=14))
                gid += 1
                day += pd.Timedelta(days=int(rng.integers(1, 3)))
    return pd.DataFrame(rows)


def test_first_game_has_neutral_features():
    f = build_features(make_games())
    first = f.iloc[0]
    assert first["elo_diff"] == 0
    assert first["season_net_rating_diff"] == 0
    assert first["rest_days_diff"] == 0


def test_a_games_result_never_changes_its_own_features():
    games = make_games()
    base = build_features(games)
    flipped = games.copy()
    i = 50
    flipped.loc[i, ["home_score", "away_score"]] = flipped.loc[i, ["away_score", "home_score"]].to_numpy()
    after = build_features(flipped)
    # Features up to and including game i are identical; later games see the changed result.
    assert np.allclose(base.loc[:i, FEATURES], after.loc[:i, FEATURES])
    assert not np.allclose(base.loc[i + 1:, FEATURES], after.loc[i + 1:, FEATURES])
    assert base.loc[i, "home_won"] != after.loc[i, "home_won"]


def test_walk_forward_never_trains_on_the_future():
    f = build_features(make_games(n_seasons=5))
    wf = walk_forward(f, first_test_season=2022)
    for fold in wf["folds"]:
        last_train = int(fold["train_seasons"].split("-")[1])
        assert last_train < fold["test_season"]
    assert wf["n_folds"] == 3
