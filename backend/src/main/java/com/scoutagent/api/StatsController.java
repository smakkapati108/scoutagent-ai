package com.scoutagent.api;

import com.scoutagent.analytics.StatsService;
import com.scoutagent.analytics.TeamDirectory;
import com.scoutagent.model.WinProbabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class StatsController {

    private final StatsService stats;
    private final TeamDirectory teams;
    private final WinProbabilityService winProb;

    public StatsController(StatsService stats, TeamDirectory teams, WinProbabilityService winProb) {
        this.stats = stats;
        this.teams = teams;
        this.winProb = winProb;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current_season", teams.currentSeason());
        m.put("seasons", stats.seasons());
        m.put("team_game_logs", stats.totalGameLogs());
        m.put("teams", teams.all());
        return m;
    }

    @GetMapping("/standings")
    public List<Map<String, Object>> standings(@RequestParam(required = false) Integer season) {
        return stats.leagueTable(season(season));
    }

    @GetMapping("/teams/{abbr}/summary")
    public Map<String, Object> summary(@PathVariable String abbr, @RequestParam(required = false) Integer season) {
        return stats.seasonSummary(teams.byAbbr(abbr).id(), season(season));
    }

    @GetMapping("/teams/{abbr}/games")
    public List<Map<String, Object>> games(@PathVariable String abbr, @RequestParam(defaultValue = "10") int limit) {
        return stats.recentGames(teams.byAbbr(abbr).id(), limit);
    }

    @GetMapping("/teams/{abbr}/trend")
    public List<Map<String, Object>> trend(@PathVariable String abbr, @RequestParam(required = false) Integer season) {
        return stats.trend(teams.byAbbr(abbr).id(), season(season));
    }

    @GetMapping("/teams/{abbr}/splits")
    public List<Map<String, Object>> splits(@PathVariable String abbr, @RequestParam(required = false) Integer season) {
        return stats.situationalSplits(teams.byAbbr(abbr).id(), season(season));
    }

    @GetMapping("/matchup")
    public Map<String, Object> matchup(@RequestParam String team, @RequestParam String opponent,
                                       @RequestParam(required = false) Integer season) {
        return stats.matchupComparison(teams.byAbbr(team).id(), teams.byAbbr(opponent).id(), season(season));
    }

    @GetMapping("/head-to-head")
    public Map<String, Object> headToHead(@RequestParam String team, @RequestParam String opponent,
                                          @RequestParam(defaultValue = "15") int limit) {
        return stats.headToHead(teams.byAbbr(team).id(), teams.byAbbr(opponent).id(), limit);
    }

    @GetMapping("/predict")
    public Map<String, Object> predict(@RequestParam String home, @RequestParam String away,
                                       @RequestParam(defaultValue = "1") int homeRest,
                                       @RequestParam(defaultValue = "1") int awayRest,
                                       @RequestParam(defaultValue = "0") double homeNetDelta,
                                       @RequestParam(defaultValue = "0") double awayNetDelta) {
        return winProb.predict(teams.byAbbr(home).id(), teams.byAbbr(away).id(),
                Math.max(0, Math.min(3, homeRest)), Math.max(0, Math.min(3, awayRest)), homeNetDelta, awayNetDelta);
    }

    @GetMapping("/model/metrics")
    public Map<String, Object> modelMetrics() {
        return winProb.metrics();
    }

    @PostMapping("/model/retrain")
    public Map<String, Object> retrain() {
        return winProb.train();
    }

    private int season(Integer season) {
        return season == null ? teams.currentSeason() : season;
    }
}
