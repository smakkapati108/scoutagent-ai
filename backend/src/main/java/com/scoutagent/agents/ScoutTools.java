package com.scoutagent.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.scoutagent.analytics.StatsService;
import com.scoutagent.analytics.TeamDirectory;
import com.scoutagent.model.WinProbabilityService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The tool catalog the agents use to query the game-log database and the win-probability model. */
@Component
public class ScoutTools {

    private final StatsService stats;
    private final TeamDirectory teams;
    private final WinProbabilityService winProb;

    public ScoutTools(StatsService stats, TeamDirectory teams, WinProbabilityService winProb) {
        this.stats = stats;
        this.teams = teams;
        this.winProb = winProb;
    }

    public List<AgentTool> analystTools() {
        return List.of(seasonSummary(), recentGames(), headToHead(), queryGameLogs(), leagueRankings());
    }

    public List<AgentTool> scoutTools() {
        return List.of(situationalSplits(), performanceTrend(), compareMatchup(), queryGameLogs(), recentGames());
    }

    public List<AgentTool> plannerTools() {
        return List.of(winProbability(), compareMatchup(), seasonSummary());
    }

    // ---- tool definitions ----

    AgentTool seasonSummary() {
        return AgentTool.named("get_team_season_summary",
                        "Season aggregates for one team: record, home/away record, points, offensive/defensive/net rating, "
                                + "pace, offensive and defensive four factors, and the team's league rank (1 = best) on each metric.")
                .string("team", "Team abbreviation, e.g. BOS", true)
                .integer("season", "Season year; defaults to the current season", false)
                .handler(in -> stats.seasonSummary(team(in, "team"), season(in)));
    }

    AgentTool recentGames() {
        return AgentTool.named("get_recent_games",
                        "The team's most recent games (newest first) with full box score lines.")
                .string("team", "Team abbreviation", true)
                .integer("limit", "Number of games, 1-82 (default 10)", false)
                .handler(in -> stats.recentGames(team(in, "team"), intOr(in, "limit", 10)));
    }

    AgentTool headToHead() {
        return AgentTool.named("get_head_to_head",
                        "Historical games between two teams across all seasons, from the first team's perspective, "
                                + "with the overall record and average margin.")
                .string("team", "Team abbreviation", true)
                .string("opponent", "Opponent abbreviation", true)
                .integer("limit", "Max games, 1-50 (default 15)", false)
                .handler(in -> stats.headToHead(team(in, "team"), team(in, "opponent"), intOr(in, "limit", 15)));
    }

    AgentTool queryGameLogs() {
        return AgentTool.named("query_game_logs",
                        "Flexible search over every historical team game log. Filter by team, opponent, seasons, venue, "
                                + "result, rest days and margin; sort by a stat. Returns an aggregate summary of all matching "
                                + "games plus up to `limit` rows. Use it to test specific hypotheses (e.g. how a team does on "
                                + "back-to-backs on the road, or its worst losses this season).")
                .string("team", "Team abbreviation (optional)", false)
                .string("opponent", "Opponent abbreviation (optional)", false)
                .integer("season_from", "First season to include (optional)", false)
                .integer("season_to", "Last season to include (optional)", false)
                .enumString("venue", "Home or away games only", List.of("home", "away"), false)
                .enumString("result", "Wins (W) or losses (L) only", List.of("W", "L"), false)
                .integer("rest_days", "Exact rest days before the game: 0 = back-to-back, 3 = three or more", false)
                .integer("min_margin", "Minimum point margin from the team's perspective", false)
                .integer("max_margin", "Maximum point margin from the team's perspective", false)
                .enumString("sort_by", "Sort column (default date)",
                        List.of("date", "points", "opp_points", "margin", "possessions", "fg3m", "tov"), false)
                .bool("descending", "Sort descending (default true)", false)
                .integer("limit", "Rows to return, 1-50 (default 20)", false)
                .handler(in -> stats.queryGameLogs(new StatsService.LogQuery(
                        optTeam(in, "team"), optTeam(in, "opponent"), optInt(in, "season_from"), optInt(in, "season_to"),
                        optText(in, "venue"), optText(in, "result"), optInt(in, "rest_days"),
                        optInt(in, "min_margin"), optInt(in, "max_margin"), optText(in, "sort_by"),
                        in.hasNonNull("descending") ? in.get("descending").asBoolean() : null, optInt(in, "limit"))));
    }

    AgentTool leagueRankings() {
        return AgentTool.named("get_league_rankings",
                        "Rank all 30 teams on one metric for a season (1 = best). Useful for context on how good a number is.")
                .enumString("metric", "Metric to rank by", List.copyOf(StatsService.RANKED_METRICS.keySet()), true)
                .integer("season", "Season year; defaults to the current season", false)
                .handler(in -> {
                    String metric = in.get("metric").asText();
                    if (!StatsService.RANKED_METRICS.containsKey(metric)) throw new IllegalArgumentException("Unknown metric " + metric);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Map<String, Object> r : stats.leagueTable(season(in))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Integer> ranks = (Map<String, Integer>) r.get("league_ranks");
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("rank", ranks.get(metric));
                        row.put("team", r.get("team"));
                        row.put(metric, r.get(metric));
                        row.put("record", r.get("wins") + "-" + r.get("losses"));
                        rows.add(row);
                    }
                    rows.sort(Comparator.comparingInt(r -> (Integer) r.get("rank")));
                    return rows;
                });
    }

    AgentTool situationalSplits() {
        return AgentTool.named("get_situational_splits",
                        "A team's performance split by situation for a season: home/away, rest (back-to-back, one day, "
                                + "two+ days), close games, vs winning/losing teams, fast/slow paced games.")
                .string("team", "Team abbreviation", true)
                .integer("season", "Season year; defaults to the current season", false)
                .handler(in -> stats.situationalSplits(team(in, "team"), season(in)));
    }

    AgentTool performanceTrend() {
        return AgentTool.named("get_performance_trend",
                        "A team's form over a season: 10-game rolling net rating sampled every 5 games, plus the last 15 games.")
                .string("team", "Team abbreviation", true)
                .integer("season", "Season year; defaults to the current season", false)
                .handler(in -> {
                    List<Map<String, Object>> series = stats.trend(team(in, "team"), season(in));
                    List<Map<String, Object>> sampled = new ArrayList<>();
                    for (int i = 4; i < series.size(); i += 5) {
                        Map<String, Object> g = series.get(i);
                        sampled.add(Map.of("game_number", g.get("game_number"),
                                "rolling10_net_rating", g.get("rolling10_net_rating"),
                                "cumulative_wins", g.get("cumulative_wins")));
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("rolling_series", sampled);
                    out.put("last_15_games", series.subList(Math.max(0, series.size() - 15), series.size()));
                    return out;
                });
    }

    AgentTool compareMatchup() {
        return AgentTool.named("compare_matchup",
                        "Head-to-head statistical matchup for a season: both teams' full summaries plus each offense's "
                                + "four factors against the other defense, with league ranks and an edge score "
                                + "(positive = the offense has the advantage).")
                .string("team", "Team abbreviation", true)
                .string("opponent", "Opponent abbreviation", true)
                .integer("season", "Season year; defaults to the current season", false)
                .handler(in -> stats.matchupComparison(team(in, "team"), team(in, "opponent"), season(in)));
    }

    AgentTool winProbability() {
        return AgentTool.named("get_win_probability",
                        "The trained win-probability model's prediction for a game, with each feature's contribution. "
                                + "Optionally simulate scenarios: rest days, or a team playing better/worse than its baseline "
                                + "(net rating delta in points per 100 possessions, e.g. +3 if a game plan is expected to "
                                + "win the turnover battle). Call it several times to compare scenarios.")
                .string("home_team", "Home team abbreviation", true)
                .string("away_team", "Away team abbreviation", true)
                .integer("home_rest_days", "Home team rest days 0-3 (default 1)", false)
                .integer("away_rest_days", "Away team rest days 0-3 (default 1)", false)
                .number("home_net_rating_delta", "Scenario adjustment to the home team's net rating (default 0)", false)
                .number("away_net_rating_delta", "Scenario adjustment to the away team's net rating (default 0)", false)
                .handler(in -> winProb.predict(team(in, "home_team"), team(in, "away_team"),
                        clampRest(intOr(in, "home_rest_days", 1)), clampRest(intOr(in, "away_rest_days", 1)),
                        in.hasNonNull("home_net_rating_delta") ? in.get("home_net_rating_delta").asDouble() : 0,
                        in.hasNonNull("away_net_rating_delta") ? in.get("away_net_rating_delta").asDouble() : 0));
    }

    // ---- input helpers ----

    private int team(JsonNode in, String field) {
        if (!in.hasNonNull(field)) throw new IllegalArgumentException("Missing required field: " + field);
        return teams.byAbbr(in.get(field).asText()).id();
    }

    private Integer optTeam(JsonNode in, String field) {
        return in.hasNonNull(field) && !in.get(field).asText().isBlank() ? team(in, field) : null;
    }

    private int season(JsonNode in) {
        return in.hasNonNull("season") ? in.get("season").asInt() : teams.currentSeason();
    }

    private static int intOr(JsonNode in, String field, int dflt) {
        return in.hasNonNull(field) ? in.get(field).asInt() : dflt;
    }

    private static Integer optInt(JsonNode in, String field) {
        return in.hasNonNull(field) ? in.get(field).asInt() : null;
    }

    private static String optText(JsonNode in, String field) {
        return in.hasNonNull(field) ? in.get(field).asText() : null;
    }

    private static int clampRest(int r) {
        return Math.max(0, Math.min(3, r));
    }
}
