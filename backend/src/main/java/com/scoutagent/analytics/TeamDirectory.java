package com.scoutagent.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** In-memory lookup of teams (they never change at runtime) and the current season. */
@Component
public class TeamDirectory {

    public record Team(int id, String abbr, String name, String conference) {}

    private final JdbcTemplate jdbc;
    private volatile Map<Integer, Team> byId = Map.of();
    private volatile Map<String, Team> byAbbr = Map.of();
    private volatile int currentSeason;

    public TeamDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void load() {
        List<Team> teams = jdbc.query("SELECT id, abbr, name, conference FROM teams ORDER BY abbr",
                (rs, i) -> new Team(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        byId = teams.stream().collect(Collectors.toMap(Team::id, Function.identity(), (a, b) -> a, ConcurrentHashMap::new));
        byAbbr = teams.stream().collect(Collectors.toMap(Team::abbr, Function.identity()));
        Integer season = jdbc.queryForObject("SELECT max(season) FROM games", Integer.class);
        currentSeason = season == null ? 0 : season;
    }

    public List<Team> all() {
        return byId.values().stream().sorted((a, b) -> a.abbr().compareTo(b.abbr())).toList();
    }

    public Team byId(int id) {
        Team t = byId.get(id);
        if (t == null) throw new IllegalArgumentException("Unknown team id: " + id);
        return t;
    }

    public Team byAbbr(String abbr) {
        Team t = abbr == null ? null : byAbbr.get(abbr.trim().toUpperCase(Locale.ROOT));
        if (t == null) throw new IllegalArgumentException("Unknown team abbreviation: " + abbr
                + ". Valid: " + String.join(", ", byAbbr.keySet().stream().sorted().toList()));
        return t;
    }

    public int currentSeason() {
        return currentSeason;
    }
}
