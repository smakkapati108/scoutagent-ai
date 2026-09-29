# ScoutAgent AI

A full-stack sports analytics and game-planning engine. A Java/Spring Boot backend serves analytics over a PostgreSQL database of team game logs and a trained win-probability model. Three Claude agents query that data through tool calls and write a tactical game plan. A React + TypeScript dashboard visualizes all of it, including the agents' work as it happens.

```
React + TS dashboard (Vite)  ──►  Spring Boot REST API (Java 21, virtual threads)
  custom SVG charts                ├─ StatsService ── PostgreSQL 16 (HikariCP pool, Flyway, indexed)
  live agent timeline (SSE)        ├─ WinProbabilityService (feature engine + logistic regression)
                                   └─ ScoutingOrchestrator
                                        ├─ Data Analyst  ─┐ run in parallel, each with its own tools
                                        ├─ Tactical Scout ┘
                                        └─ Game Planner  (reads both reports, runs model scenarios)
```

## Quick start

Prerequisites: Java 21, Maven, Node 20+, Docker.

```bash
docker compose up -d                          # PostgreSQL on localhost:5433

export ANTHROPIC_API_KEY=sk-ant-...           # needed only for the scouting agents
cd backend && mvn spring-boot:run             # http://localhost:8080

cd frontend && npm install && npm run dev     # http://localhost:5173
```

On first start the backend creates the schema, seeds the database (a few seconds), and trains the model (under 1 s). Everything except the scouting agents works without an API key.

## Data

The database is seeded with a **synthetic** league: 30 teams, 10 seasons (2016–2025), 12,300 games and **24,600 team game logs** with full box scores. Each team has latent offense, defense, pace and style ratings that drift within and between seasons. Schedules produce realistic rest patterns (about 12 back-to-backs per team per season). Box scores are generated from ratings plus game-level noise and always add up (points = 2·FG2 + 3·FG3 + FT). The generator is seeded, so every run produces the same league.

To use real data, load it into the same three tables (`teams`, `games`, `team_game_logs`) and set `scout.seed.enabled=false`.

## Win-probability model

`FeatureEngine` replays games in date order and builds 9 features per game using only information available before tip-off:

- Elo gap
- Season-to-date net rating gap, shrunk toward half of last season's rating early in the season
- Last-10 net rating gap
- Win % gap
- Rest-day difference and back-to-back flags
- eFG% gap and turnover-rate gap

An L2-regularized logistic regression, written from scratch, trains on 2017–2022; seasons 2023–2025 are held out. Metrics are computed on every startup and served by `GET /api/model/metrics`. Nothing is hard-coded.

Current results on the seeded league (3,690 held-out games):

| | Accuracy |
|---|---|
| **ScoutAgent model** | **66.5%** (log loss 0.609, Brier 0.211) |
| Elo only | 65.2% |
| Better record so far | 63.5% |
| Always pick home team | 57.8% |

That is a relative lift of about 15% over the home-team baseline, and the model is well calibrated (see the dashboard's calibration chart). Expect figures in this range: real NBA models typically reach 65–70%, because single games are noisy.

## Agents

Each agent runs a manual Claude tool-use loop (`AgentRunner`) with the official Anthropic Java SDK:

- Default model `claude-opus-5`, with adaptive thinking and a configurable effort level.
- Prompt caching is on for the conversation prefix across turns.
- Tool calls within a turn run in parallel on virtual threads.
- The loop checks for `refusal` and `max_tokens` stop reasons.
- Server-side refusal fallbacks (beta) are enabled; turn them off with `scout.agents.refusal-fallbacks=false`.

| Agent | Tools |
|---|---|
| Data Analyst | `get_team_season_summary`, `get_recent_games`, `get_head_to_head`, `query_game_logs`, `get_league_rankings` |
| Tactical Scout | `get_situational_splits`, `get_performance_trend`, `compare_matchup`, `query_game_logs`, `get_recent_games` |
| Game Planner | `get_win_probability` (with rest / net-rating scenarios), `compare_matchup`, `get_team_season_summary` |

`query_game_logs` is a parameterized search over every game log. It filters by team, opponent, seasons, venue, result, rest and margin, and sorts by a whitelisted column. The Analyst and Scout run concurrently, then the Planner synthesizes their reports. Progress streams to the UI over server-sent events (`GET /api/scouting/stream?home=BOS&away=DEN&focus=BOS`). Finished reports, with their duration and tool-call count, are stored in `scouting_reports`.

Configuration lives in `backend/src/main/resources/application.yml`, under `scout.agents.*`. Set the model with the `SCOUT_AGENT_MODEL` environment variable.

## API

| Endpoint | Description |
|---|---|
| `GET /api/meta` | Teams, seasons, log count |
| `GET /api/standings?season=` | League table with four factors and league ranks |
| `GET /api/teams/{abbr}/summary` · `/games` · `/trend` · `/splits` | Team analytics |
| `GET /api/matchup?team=&opponent=` | Four-factor matchup edges |
| `GET /api/head-to-head?team=&opponent=` | Historical series |
| `GET /api/predict?home=&away=&homeRest=&awayRest=&homeNetDelta=&awayNetDelta=` | Win probability and factor contributions |
| `GET /api/model/metrics` · `POST /api/model/retrain` | Model evaluation / retraining |
| `GET /api/scouting/stream` · `/reports` · `/reports/{id}` | Agent runs and saved reports |

## Performance

The database layer is tuned in three places:

- **Indexes:** composite and covering indexes on the game-log access paths (`team_id, game_date DESC`, `team_id, season INCLUDE (...)`, `team_id, opponent_id, game_date DESC`).
- **Connection pool:** HikariCP with server-side prepared-statement caching.
- **Caching:** a Caffeine cache on the league table.

Tomcat runs on virtual threads.

Load test (a mix of 7 read endpoints, 64 concurrent clients, 20 s, measured on an Apple-silicon laptop with Postgres in Docker):

```bash
cd loadtest && npm install && node loadtest.mjs --concurrency 64 --seconds 20
```

Result: about **4,000 requests/s**, 0 errors, p50 14 ms, p95 41 ms, p99 57 ms. Results depend on hardware.

## Tests

```bash
cd backend && mvn test     # needs `docker compose up -d`
```

- Generator invariants: 82 games per team, no double-booking, consistent box scores, realistic rest.
- The regression recovers known coefficients.
- An integration test calls every agent tool exactly as Claude would (JSON in, JSON out) against the seeded database.
