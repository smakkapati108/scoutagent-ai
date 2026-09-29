package com.scoutagent.agents;

import com.anthropic.models.messages.OutputConfig;
import com.scoutagent.analytics.StatsService;
import com.scoutagent.analytics.TeamDirectory;
import com.scoutagent.model.WinProbabilityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates the three agents. The Data Analyst and Tactical Scout investigate in parallel, each with its own
 * tools; the Game Planner then reads both reports, runs model scenarios, and writes the final game plan.
 */
@Service
public class ScoutingOrchestrator {

    static final String ANALYST = "data_analyst";
    static final String SCOUT = "tactical_scout";
    static final String PLANNER = "game_planner";

    private static final String ANALYST_SYSTEM = """
            You are the Data Analyst on an NBA-style coaching staff. You have tools that query a database of every
            team game log in the league's history. Build the quantitative picture of an upcoming matchup: each team's
            season profile and league ranks, recent results, head-to-head history, and any statistical patterns you
            can verify in the game logs. Every claim must come from a tool result; cite the numbers. Investigate
            efficiently: make independent tool calls in parallel.

            Finish with a concise markdown briefing (no preamble) with these sections:
            ## Season profiles, ## Recent form, ## Head-to-head, ## Statistical edges (bulleted, most important first).
            """;

    private static final String SCOUT_SYSTEM = """
            You are the Tactical Scout on an NBA-style coaching staff. Using your tools, study how the opponent wins
            and loses: situational splits (home/away, rest, close games, opponent quality, pace), form trends, and the
            four-factor matchup against our team. Look for exploitable tendencies and for our own vulnerabilities in
            this matchup. Every claim must come from a tool result; cite the numbers. Make independent tool calls in
            parallel.

            Finish with a concise markdown scouting report (no preamble) with these sections:
            ## How the opponent wins, ## How the opponent loses, ## Four-factor matchup, ## Situational factors,
            ## Exploitable tendencies (bulleted).
            """;

    private static final String PLANNER_SYSTEM = """
            You are the Head Coach's Game Planner. You receive a Data Analyst briefing and a Tactical Scout report.
            Turn them into an actionable game plan for the focus team. Use get_win_probability to establish the
            baseline win probability and to quantify scenarios (for example: what the probability becomes if the plan
            is worth +2 or +4 net rating, or if rest changes). Ground recommendations in the reports' numbers and your
            own tool results; don't invent statistics.

            Output only the final markdown game plan with these sections:
            # Game Plan: <focus team> vs <opponent>
            ## Bottom line (win probability, and the 2-3 things that decide the game)
            ## Offensive keys, ## Defensive keys, ## Rotation and pace, ## Scenario analysis (a small table of
            scenario -> win probability), ## Risks and counters.
            """;

    public record ReportRequest(String homeTeam, String awayTeam, String focusTeam) {}

    private final AgentRunner runner;
    private final ScoutTools tools;
    private final TeamDirectory teams;
    private final WinProbabilityService winProb;
    private final StatsService stats;
    private final JdbcTemplate jdbc;
    private final OutputConfig.Effort analystEffort;
    private final OutputConfig.Effort plannerEffort;

    public ScoutingOrchestrator(AgentRunner runner, ScoutTools tools, TeamDirectory teams,
                                WinProbabilityService winProb, StatsService stats, JdbcTemplate jdbc,
                                @Value("${scout.agents.analyst-effort}") String analystEffort,
                                @Value("${scout.agents.planner-effort}") String plannerEffort) {
        this.runner = runner;
        this.tools = tools;
        this.teams = teams;
        this.winProb = winProb;
        this.stats = stats;
        this.jdbc = jdbc;
        this.analystEffort = OutputConfig.Effort.of(analystEffort);
        this.plannerEffort = OutputConfig.Effort.of(plannerEffort);
    }

    public Map<String, Object> run(ReportRequest req, AgentRunner.Listener listener) {
        TeamDirectory.Team home = teams.byAbbr(req.homeTeam());
        TeamDirectory.Team away = teams.byAbbr(req.awayTeam());
        if (home.id() == away.id()) throw new IllegalArgumentException("Pick two different teams");
        TeamDirectory.Team focus = req.focusTeam() == null || req.focusTeam().isBlank() ? home : teams.byAbbr(req.focusTeam());
        if (focus.id() != home.id() && focus.id() != away.id()) {
            throw new IllegalArgumentException("Focus team must be one of the two teams in the game");
        }
        TeamDirectory.Team opponent = focus.id() == home.id() ? away : home;
        int season = teams.currentSeason();
        long start = System.currentTimeMillis();

        String matchup = """
                Upcoming game: %s (%s, home) vs %s (%s, away). Current season: %d.
                We are game-planning for %s; the opponent is %s.
                The database holds %d team game logs across seasons %s."""
                .formatted(home.abbr(), home.name(), away.abbr(), away.name(), season, focus.abbr(), opponent.abbr(),
                        stats.totalGameLogs(), stats.seasons());

        listener.onEvent("run_started", "orchestrator", Map.of("home", home.abbr(), "away", away.abbr(),
                "focus", focus.abbr(), "model", runner.model()));

        AgentRunner.AgentResult analyst, scout;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var analystF = CompletableFuture.supplyAsync(() -> {
                listener.onEvent("agent_started", ANALYST, Map.of());
                return runner.run(ANALYST, ANALYST_SYSTEM, matchup + "\n\nProduce your data briefing.",
                        tools.analystTools(), analystEffort, listener);
            }, pool);
            var scoutF = CompletableFuture.supplyAsync(() -> {
                listener.onEvent("agent_started", SCOUT, Map.of());
                return runner.run(SCOUT, SCOUT_SYSTEM, matchup + "\n\nProduce your scouting report on "
                        + opponent.abbr() + " for " + focus.abbr() + ".", tools.scoutTools(), analystEffort, listener);
            }, pool);
            analyst = analystF.join();
            scout = scoutF.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : e;
        }
        listener.onEvent("agent_output", ANALYST, Map.of("text", analyst.text()));
        listener.onEvent("agent_output", SCOUT, Map.of("text", scout.text()));

        listener.onEvent("agent_started", PLANNER, Map.of());
        AgentRunner.AgentResult planner = runner.run(PLANNER, PLANNER_SYSTEM, matchup
                        + "\n\n<data_analyst_briefing>\n" + analyst.text() + "\n</data_analyst_briefing>\n\n"
                        + "<tactical_scout_report>\n" + scout.text() + "\n</tactical_scout_report>\n\n"
                        + "Write the game plan for " + focus.abbr() + ".",
                tools.plannerTools(), plannerEffort, listener);
        listener.onEvent("agent_output", PLANNER, Map.of("text", planner.text()));

        Map<String, Object> baseline = winProb.predict(home.id(), away.id(), 1, 1, 0, 0);
        double focusWinProb = (double) baseline.get(focus.id() == home.id() ? "home_win_probability" : "away_win_probability");
        long durationMs = System.currentTimeMillis() - start;
        int toolCalls = analyst.toolCalls() + scout.toolCalls() + planner.toolCalls();

        Long id = jdbc.queryForObject("""
                INSERT INTO scouting_reports (home_team_id, away_team_id, focus_team_id, win_probability, duration_ms,
                    model, analyst_notes, scout_notes, game_plan, tool_calls)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                home.id(), away.id(), focus.id(), focusWinProb, (int) durationMs, runner.model(),
                analyst.text(), scout.text(), planner.text(), toolCalls);

        Map<String, Object> usage = new LinkedHashMap<>();
        for (var r : List.of(analyst, scout, planner)) {
            usage.put(r.agent(), Map.of("turns", r.turns(), "tool_calls", r.toolCalls(),
                    "input_tokens", r.inputTokens(), "output_tokens", r.outputTokens()));
        }
        Map<String, Object> report = findReport(id);
        report.put("usage", usage);
        listener.onEvent("report_completed", "orchestrator", report);
        return report;
    }

    public List<Map<String, Object>> listReports(int limit) {
        return jdbc.queryForList("""
                SELECT r.id, r.created_at, h.abbr AS home, a.abbr AS away, f.abbr AS focus, r.win_probability,
                       r.duration_ms, r.tool_calls, r.model
                FROM scouting_reports r
                JOIN teams h ON h.id = r.home_team_id JOIN teams a ON a.id = r.away_team_id JOIN teams f ON f.id = r.focus_team_id
                ORDER BY r.created_at DESC LIMIT ?""", limit);
    }

    public Map<String, Object> findReport(long id) {
        return new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT r.id, r.created_at, h.abbr AS home, a.abbr AS away, f.abbr AS focus, r.win_probability,
                       r.duration_ms, r.tool_calls, r.model, r.analyst_notes, r.scout_notes, r.game_plan
                FROM scouting_reports r
                JOIN teams h ON h.id = r.home_team_id JOIN teams a ON a.id = r.away_team_id JOIN teams f ON f.id = r.focus_team_id
                WHERE r.id = ?""", id));
    }
}
