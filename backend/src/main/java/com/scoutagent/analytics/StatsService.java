package com.scoutagent.analytics;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Read-side analytics over the game logs. Every method returns JSON-friendly maps so the same results serve
 * both the REST API and the agents' tools.
 */
@Service
public class StatsService {

    /** Metrics shown with league ranks. "true" means higher is better. */
    public static final Map<String, Boolean> RANKED_METRICS;
    static {
        RANKED_METRICS = new LinkedHashMap<>();
        RANKED_METRICS.put("win_pct", true);
        RANKED_METRICS.put("off_rating", true);
        RANKED_METRICS.put("def_rating", false);
        RANKED_METRICS.put("net_rating", true);
        RANKED_METRICS.put("pace", true);
        RANKED_METRICS.put("efg_pct", true);
        RANKED_METRICS.put("tov_pct", false);
        RANKED_METRICS.put("oreb_pct", true);
        RANKED_METRICS.put("ft_rate", true);
        RANKED_METRICS.put("three_rate", true);
        RANKED_METRICS.put("three_pct", true);
        RANKED_METRICS.put("opp_efg_pct", false);
        RANKED_METRICS.put("opp_tov_pct", true);
        RANKED_METRICS.put("dreb_pct", true);
        RANKED_METRICS.put("opp_ft_rate", false);
    }

    private static final Set<String> SORTABLE = Set.of("date", "points", "opp_points", "margin", "possessions", "fg3m", "tov");

    private final JdbcTemplate jdbc;
    private final TeamDirectory teams;

    public StatsService(JdbcTemplate jdbc, TeamDirectory teams) {
        this.jdbc = jdbc;
        this.teams = teams;
    }

    /** Season aggregates (with offensive and defensive four factors) for every team, including league ranks. */
    @Cacheable("leagueTable")
    public List<Map<String, Object>> leagueTable(int season) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.team_id,
                       count(*)                                                        AS games,
                       sum(t.won::int)                                                 AS wins,
                       count(*) - sum(t.won::int)                                      AS losses,
                       round(avg(t.won::int), 3)                                       AS win_pct,
                       sum((t.is_home AND t.won)::int) || '-' || sum((t.is_home AND NOT t.won)::int)       AS home_record,
                       sum((NOT t.is_home AND t.won)::int) || '-' || sum((NOT t.is_home AND NOT t.won)::int) AS away_record,
                       round(avg(t.points), 1)                                         AS ppg,
                       round(avg(t.opp_points), 1)                                     AS opp_ppg,
                       round((100.0 * sum(t.points) / sum(t.possessions))::numeric, 1)      AS off_rating,
                       round((100.0 * sum(t.opp_points) / sum(t.possessions))::numeric, 1)  AS def_rating,
                       round((100.0 * (sum(t.points) - sum(t.opp_points)) / sum(t.possessions))::numeric, 1) AS net_rating,
                       round(avg(t.possessions)::numeric, 1)                           AS pace,
                       round((sum(t.fgm) + 0.5 * sum(t.fg3m)) / sum(t.fga)::numeric, 3) AS efg_pct,
                       round((sum(t.tov) / sum(t.possessions))::numeric, 3)            AS tov_pct,
                       round(sum(t.oreb)::numeric / nullif(sum(t.oreb) + sum(o.dreb), 0), 3) AS oreb_pct,
                       round(sum(t.fta)::numeric / sum(t.fga), 3)                      AS ft_rate,
                       round(sum(t.fg3a)::numeric / sum(t.fga), 3)                     AS three_rate,
                       round(sum(t.fg3m)::numeric / nullif(sum(t.fg3a), 0), 3)         AS three_pct,
                       round((sum(o.fgm) + 0.5 * sum(o.fg3m)) / sum(o.fga)::numeric, 3) AS opp_efg_pct,
                       round((sum(o.tov) / sum(o.possessions))::numeric, 3)            AS opp_tov_pct,
                       round(sum(t.dreb)::numeric / nullif(sum(t.dreb) + sum(o.oreb), 0), 3) AS dreb_pct,
                       round(sum(o.fta)::numeric / sum(o.fga), 3)                      AS opp_ft_rate
                FROM team_game_logs t
                JOIN team_game_logs o ON o.game_id = t.game_id AND o.team_id = t.opponent_id
                WHERE t.season = ?
                GROUP BY t.team_id
                """, season);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            TeamDirectory.Team team = teams.byId(((Number) r.get("team_id")).intValue());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team", team.abbr());
            m.put("team_name", team.name());
            m.put("conference", team.conference());
            m.put("season", season);
            m.putAll(r);
            out.add(m);
        }
        Map<String, Map<String, Integer>> ranks = new LinkedHashMap<>();
        for (var metric : RANKED_METRICS.entrySet()) {
            ToDoubleFunction<Map<String, Object>> f = row -> num(row.get(metric.getKey()));
            Comparator<Map<String, Object>> cmp = Comparator.comparingDouble(f);
            if (metric.getValue()) cmp = cmp.reversed();
            List<Map<String, Object>> sorted = out.stream().sorted(cmp).toList();
            for (int i = 0; i < sorted.size(); i++) {
                ranks.computeIfAbsent((String) sorted.get(i).get("team"), k -> new LinkedHashMap<>())
                        .put(metric.getKey(), i + 1);
            }
        }
        out.forEach(m -> m.put("league_ranks", ranks.get((String) m.get("team"))));
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> num(m.get("win_pct"))).reversed());
        return out;
    }

    public Map<String, Object> seasonSummary(int teamId, int season) {
        String abbr = teams.byId(teamId).abbr();
        return leagueTable(season).stream().filter(m -> abbr.equals(m.get("team"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No games for " + abbr + " in season " + season));
    }

    /** Side-by-side matchup of each team's offense against the other's defense, with league ranks. */
    public Map<String, Object> matchupComparison(int teamId, int opponentId, int season) {
        Map<String, Object> a = seasonSummary(teamId, season);
        Map<String, Object> b = seasonSummary(opponentId, season);
        List<Map<String, Object>> edges = new ArrayList<>();
        String[][] pairs = {
                {"efg_pct", "opp_efg_pct", "Shooting efficiency"},
                {"tov_pct", "opp_tov_pct", "Ball security vs pressure"},
                {"oreb_pct", "dreb_pct", "Offensive glass vs defensive glass"},
                {"ft_rate", "opp_ft_rate", "Getting to the line vs fouling"},
        };
        for (String[] p : pairs) {
            edges.add(edge(a, b, p[0], p[1], p[2] + " (" + a.get("team") + " offense)"));
            edges.add(edge(b, a, p[0], p[1], p[2] + " (" + b.get("team") + " offense)"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("season", season);
        out.put("team", a);
        out.put("opponent", b);
        out.put("four_factor_edges", edges);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> edge(Map<String, Object> off, Map<String, Object> def, String offKey, String defKey, String label) {
        Map<String, Integer> offRanks = (Map<String, Integer>) off.get("league_ranks");
        Map<String, Integer> defRanks = (Map<String, Integer>) def.get("league_ranks");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("factor", label);
        e.put("offense_team", off.get("team"));
        e.put("offense_value", off.get(offKey));
        e.put("offense_rank", offRanks.get(offKey));
        e.put("defense_team", def.get("team"));
        e.put("defense_value", def.get(defKey));
        e.put("defense_rank", defRanks.get(defKey));
        // Positive = offense has the edge (lower rank number is better on both sides).
        e.put("edge_for_offense", defRanks.get(defKey) - offRanks.get(offKey));
        return e;
    }

    public List<Map<String, Object>> recentGames(int teamId, int limit) {
        return jdbc.queryForList("""
                SELECT t.game_date, t.season, o.abbr AS opponent, CASE WHEN t.is_home THEN 'home' ELSE 'away' END AS venue,
                       CASE WHEN t.won THEN 'W' ELSE 'L' END AS result, t.points, t.opp_points,
                       t.points - t.opp_points AS margin, t.possessions, t.rest_days,
                       t.fgm, t.fga, t.fg3m, t.fg3a, t.ftm, t.fta, t.oreb, t.dreb, t.ast, t.tov, t.stl, t.blk
                FROM team_game_logs t JOIN teams o ON o.id = t.opponent_id
                WHERE t.team_id = ?
                ORDER BY t.game_date DESC
                LIMIT ?""", teamId, Math.min(Math.max(limit, 1), 82));
    }

    public Map<String, Object> headToHead(int teamId, int opponentId, int limit) {
        List<Map<String, Object>> games = jdbc.queryForList("""
                SELECT t.game_date, t.season, CASE WHEN t.is_home THEN 'home' ELSE 'away' END AS venue,
                       CASE WHEN t.won THEN 'W' ELSE 'L' END AS result, t.points, t.opp_points,
                       t.points - t.opp_points AS margin, t.possessions,
                       round((t.fgm + 0.5 * t.fg3m)::numeric / t.fga, 3) AS efg_pct, t.tov, t.oreb, t.fg3m, t.fg3a
                FROM team_game_logs t
                WHERE t.team_id = ? AND t.opponent_id = ?
                ORDER BY t.game_date DESC
                LIMIT ?""", teamId, opponentId, Math.min(Math.max(limit, 1), 50));
        long wins = games.stream().filter(g -> "W".equals(g.get("result"))).count();
        double avgMargin = games.stream().mapToDouble(g -> num(g.get("margin"))).average().orElse(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team", teams.byId(teamId).abbr());
        out.put("opponent", teams.byId(opponentId).abbr());
        out.put("record", wins + "-" + (games.size() - wins));
        out.put("avg_margin", Math.round(avgMargin * 10) / 10.0);
        out.put("games", games);
        return out;
    }

    /** Performance split by venue, rest, game closeness and opponent quality. */
    public List<Map<String, Object>> situationalSplits(int teamId, int season) {
        return jdbc.queryForList("""
                WITH opp_rec AS (
                    SELECT team_id, avg(won::int) AS wp FROM team_game_logs WHERE season = ? GROUP BY team_id
                )
                SELECT s.split,
                       count(*)                                  AS games,
                       sum(t.won::int) || '-' || sum((NOT t.won)::int) AS record,
                       round(avg(t.won::int), 3)                 AS win_pct,
                       round(avg(t.points - t.opp_points), 1)    AS avg_margin,
                       round((100.0 * sum(t.points) / sum(t.possessions))::numeric, 1)     AS off_rating,
                       round((100.0 * sum(t.opp_points) / sum(t.possessions))::numeric, 1) AS def_rating,
                       round(avg(t.possessions)::numeric, 1)     AS pace,
                       round(avg(t.tov), 1)                      AS tov_per_game,
                       round(sum(t.fg3m)::numeric / nullif(sum(t.fg3a), 0), 3) AS three_pct
                FROM team_game_logs t
                JOIN opp_rec r ON r.team_id = t.opponent_id
                CROSS JOIN LATERAL (VALUES
                    (CASE WHEN t.is_home THEN 'home' ELSE 'away' END),
                    (CASE WHEN t.rest_days = 0 THEN 'back_to_back' WHEN t.rest_days = 1 THEN 'one_day_rest' ELSE 'two_plus_days_rest' END),
                    (CASE WHEN abs(t.points - t.opp_points) <= 5 THEN 'close_games_within_5' END),
                    (CASE WHEN r.wp >= 0.5 THEN 'vs_winning_teams' ELSE 'vs_losing_teams' END),
                    (CASE WHEN t.possessions >= 101 THEN 'fast_paced_games' WHEN t.possessions <= 97 THEN 'slow_paced_games' END)
                ) AS s(split)
                WHERE t.team_id = ? AND t.season = ? AND s.split IS NOT NULL
                GROUP BY s.split
                ORDER BY s.split""", season, teamId, season);
    }

    /** Game-by-game series with a 10-game rolling net rating, for trend charts. */
    public List<Map<String, Object>> trend(int teamId, int season) {
        return jdbc.queryForList("""
                SELECT row_number() OVER w AS game_number, t.game_date, o.abbr AS opponent,
                       CASE WHEN t.won THEN 'W' ELSE 'L' END AS result, t.points, t.opp_points,
                       round((100.0 * (t.points - t.opp_points) / t.possessions)::numeric, 1) AS net_rating,
                       round(avg(100.0 * (t.points - t.opp_points) / t.possessions) OVER
                             (w ROWS BETWEEN 9 PRECEDING AND CURRENT ROW)::numeric, 1) AS rolling10_net_rating,
                       sum(t.won::int) OVER w AS cumulative_wins
                FROM team_game_logs t JOIN teams o ON o.id = t.opponent_id
                WHERE t.team_id = ? AND t.season = ?
                WINDOW w AS (ORDER BY t.game_date)
                ORDER BY t.game_date""", teamId, season);
    }

    public record LogQuery(Integer teamId, Integer opponentId, Integer seasonFrom, Integer seasonTo, String venue,
                           String result, Integer restDays, Integer minMargin, Integer maxMargin,
                           String sortBy, Boolean descending, Integer limit) {}

    /** Flexible, parameterized search over the game logs, returning matching rows plus an aggregate summary. */
    public Map<String, Object> queryGameLogs(LogQuery q) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q.teamId() != null) { where.append(" AND t.team_id = ?"); args.add(q.teamId()); }
        if (q.opponentId() != null) { where.append(" AND t.opponent_id = ?"); args.add(q.opponentId()); }
        if (q.seasonFrom() != null) { where.append(" AND t.season >= ?"); args.add(q.seasonFrom()); }
        if (q.seasonTo() != null) { where.append(" AND t.season <= ?"); args.add(q.seasonTo()); }
        if ("home".equalsIgnoreCase(q.venue())) where.append(" AND t.is_home");
        if ("away".equalsIgnoreCase(q.venue())) where.append(" AND NOT t.is_home");
        if ("W".equalsIgnoreCase(q.result())) where.append(" AND t.won");
        if ("L".equalsIgnoreCase(q.result())) where.append(" AND NOT t.won");
        if (q.restDays() != null) { where.append(" AND t.rest_days = ?"); args.add(q.restDays()); }
        if (q.minMargin() != null) { where.append(" AND t.points - t.opp_points >= ?"); args.add(q.minMargin()); }
        if (q.maxMargin() != null) { where.append(" AND t.points - t.opp_points <= ?"); args.add(q.maxMargin()); }

        String sort = q.sortBy() == null ? "date" : q.sortBy().toLowerCase();
        if (!SORTABLE.contains(sort)) throw new IllegalArgumentException("sort_by must be one of " + SORTABLE);
        String sortExpr = switch (sort) {
            case "date" -> "t.game_date";
            case "margin" -> "(t.points - t.opp_points)";
            default -> "t." + sort;
        };
        boolean desc = q.descending() == null || q.descending();
        int limit = Math.min(Math.max(q.limit() == null ? 20 : q.limit(), 1), 50);

        Map<String, Object> summary = jdbc.queryForMap("""
                SELECT count(*) AS matched_games,
                       coalesce(sum(t.won::int), 0) || '-' || coalesce(sum((NOT t.won)::int), 0) AS record,
                       round(avg(t.points - t.opp_points), 1) AS avg_margin,
                       round(avg(t.points), 1) AS avg_points, round(avg(t.opp_points), 1) AS avg_opp_points,
                       round((100.0 * sum(t.points) / nullif(sum(t.possessions), 0))::numeric, 1) AS off_rating,
                       round((100.0 * sum(t.opp_points) / nullif(sum(t.possessions), 0))::numeric, 1) AS def_rating
                FROM team_game_logs t""" + where, args.toArray());

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(limit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.game_date, t.season, tm.abbr AS team, o.abbr AS opponent,
                       CASE WHEN t.is_home THEN 'home' ELSE 'away' END AS venue,
                       CASE WHEN t.won THEN 'W' ELSE 'L' END AS result, t.points, t.opp_points,
                       t.points - t.opp_points AS margin, t.possessions, t.rest_days,
                       t.fg3m, t.fg3a, t.tov, t.oreb, t.ast
                FROM team_game_logs t
                JOIN teams tm ON tm.id = t.team_id
                JOIN teams o ON o.id = t.opponent_id""" + where
                + " ORDER BY " + sortExpr + (desc ? " DESC" : " ASC") + ", t.game_id LIMIT ?", rowArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("rows", rows);
        return out;
    }

    public long totalGameLogs() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM team_game_logs", Long.class);
        return n == null ? 0 : n;
    }

    public List<Integer> seasons() {
        return jdbc.queryForList("SELECT DISTINCT season FROM games ORDER BY season", Integer.class);
    }

    static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }
}
