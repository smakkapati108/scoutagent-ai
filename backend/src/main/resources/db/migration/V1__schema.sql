CREATE TABLE teams (
    id          SMALLINT PRIMARY KEY,
    abbr        VARCHAR(4)  NOT NULL UNIQUE,
    name        VARCHAR(64) NOT NULL,
    conference  VARCHAR(8)  NOT NULL
);

CREATE TABLE games (
    id            INTEGER PRIMARY KEY,
    season        SMALLINT NOT NULL,
    game_date     DATE     NOT NULL,
    home_team_id  SMALLINT NOT NULL REFERENCES teams(id),
    away_team_id  SMALLINT NOT NULL REFERENCES teams(id),
    home_score    SMALLINT NOT NULL,
    away_score    SMALLINT NOT NULL,
    overtime      BOOLEAN  NOT NULL DEFAULT FALSE
);

-- One row per team per game (two rows per game): the "game log".
CREATE TABLE team_game_logs (
    game_id      INTEGER  NOT NULL REFERENCES games(id),
    team_id      SMALLINT NOT NULL REFERENCES teams(id),
    opponent_id  SMALLINT NOT NULL REFERENCES teams(id),
    season       SMALLINT NOT NULL,
    game_date    DATE     NOT NULL,
    is_home      BOOLEAN  NOT NULL,
    rest_days    SMALLINT NOT NULL,
    won          BOOLEAN  NOT NULL,
    points       SMALLINT NOT NULL,
    opp_points   SMALLINT NOT NULL,
    possessions  REAL     NOT NULL,
    fgm SMALLINT NOT NULL, fga SMALLINT NOT NULL,
    fg3m SMALLINT NOT NULL, fg3a SMALLINT NOT NULL,
    ftm SMALLINT NOT NULL, fta SMALLINT NOT NULL,
    oreb SMALLINT NOT NULL, dreb SMALLINT NOT NULL,
    ast SMALLINT NOT NULL, tov SMALLINT NOT NULL,
    stl SMALLINT NOT NULL, blk SMALLINT NOT NULL,
    PRIMARY KEY (game_id, team_id)
);

-- Access paths used by the API and agent tools.
CREATE INDEX idx_games_date            ON games (game_date);
CREATE INDEX idx_games_season          ON games (season);
CREATE INDEX idx_tgl_team_date         ON team_game_logs (team_id, game_date DESC);
CREATE INDEX idx_tgl_team_season       ON team_game_logs (team_id, season) INCLUDE (won, points, opp_points, possessions, is_home);
CREATE INDEX idx_tgl_team_opp_date     ON team_game_logs (team_id, opponent_id, game_date DESC);
CREATE INDEX idx_tgl_season            ON team_game_logs (season);

CREATE TABLE scouting_reports (
    id             BIGSERIAL PRIMARY KEY,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    home_team_id   SMALLINT NOT NULL REFERENCES teams(id),
    away_team_id   SMALLINT NOT NULL REFERENCES teams(id),
    focus_team_id  SMALLINT NOT NULL REFERENCES teams(id),
    win_probability REAL,
    duration_ms    INTEGER  NOT NULL,
    model          VARCHAR(64) NOT NULL,
    analyst_notes  TEXT NOT NULL,
    scout_notes    TEXT NOT NULL,
    game_plan      TEXT NOT NULL,
    tool_calls     INTEGER NOT NULL
);
CREATE INDEX idx_reports_created ON scouting_reports (created_at DESC);
