package com.scoutagent.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.util.List;

/** Populates an empty database with a synthetic league. Runs before the model trains (see ModelBootstrap). */
@Component
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int firstSeason;
    private final int lastSeason;
    private final long seed;

    public DataSeeder(JdbcTemplate jdbc, TransactionTemplate tx,
                      @Value("${scout.seed.enabled}") boolean enabled,
                      @Value("${scout.seed.first-season}") int firstSeason,
                      @Value("${scout.seed.last-season}") int lastSeason,
                      @Value("${scout.seed.random-seed}") long seed) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.enabled = enabled;
        this.firstSeason = firstSeason;
        this.lastSeason = lastSeason;
        this.seed = seed;
    }

    public void seedIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM games", Integer.class);
        if (!enabled || (count != null && count > 0)) {
            log.info("Skipping seed: {} games already present", count);
            return;
        }
        long start = System.currentTimeMillis();
        var league = new SyntheticLeagueGenerator(seed).generate(firstSeason, lastSeason);

        tx.executeWithoutResult(status -> {
            jdbc.batchUpdate("INSERT INTO teams (id, abbr, name, conference) VALUES (?, ?, ?, ?)",
                    league.teams(), 100, (ps, t) -> {
                        ps.setInt(1, t.id());
                        ps.setString(2, t.abbr());
                        ps.setString(3, t.name());
                        ps.setString(4, t.conference());
                    });
            jdbc.batchUpdate("""
                    INSERT INTO games (id, season, game_date, home_team_id, away_team_id, home_score, away_score, overtime)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", league.games(), 1000, (ps, g) -> {
                ps.setInt(1, g.id());
                ps.setInt(2, g.season());
                ps.setDate(3, Date.valueOf(g.date()));
                ps.setInt(4, g.homeId());
                ps.setInt(5, g.awayId());
                ps.setInt(6, g.homeScore());
                ps.setInt(7, g.awayScore());
                ps.setBoolean(8, g.overtime());
            });
            insertLogs(league.logs());
        });
        log.info("Seeded {} teams, {} games, {} team game logs in {} ms", league.teams().size(),
                league.games().size(), league.logs().size(), System.currentTimeMillis() - start);
        jdbc.execute("ANALYZE");
    }

    private void insertLogs(List<SyntheticLeagueGenerator.Log> logs) {
        jdbc.batchUpdate("""
                INSERT INTO team_game_logs (game_id, team_id, opponent_id, season, game_date, is_home, rest_days, won,
                    points, opp_points, possessions, fgm, fga, fg3m, fg3a, ftm, fta, oreb, dreb, ast, tov, stl, blk)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", logs, 2000, (ps, l) -> {
            int i = 1;
            ps.setInt(i++, l.gameId());
            ps.setInt(i++, l.teamId());
            ps.setInt(i++, l.opponentId());
            ps.setInt(i++, l.season());
            ps.setDate(i++, Date.valueOf(l.date()));
            ps.setBoolean(i++, l.home());
            ps.setInt(i++, l.restDays());
            ps.setBoolean(i++, l.won());
            ps.setInt(i++, l.points());
            ps.setInt(i++, l.oppPoints());
            ps.setDouble(i++, l.possessions());
            for (int v : new int[]{l.fgm(), l.fga(), l.fg3m(), l.fg3a(), l.ftm(), l.fta(),
                    l.oreb(), l.dreb(), l.ast(), l.tov(), l.stl(), l.blk()}) {
                ps.setInt(i++, v);
            }
        });
    }
}
