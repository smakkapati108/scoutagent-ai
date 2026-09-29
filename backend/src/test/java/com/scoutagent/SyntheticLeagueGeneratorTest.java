package com.scoutagent;

import com.scoutagent.data.SyntheticLeagueGenerator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticLeagueGeneratorTest {

    private final SyntheticLeagueGenerator.League league = new SyntheticLeagueGenerator(7).generate(2024, 2025);

    @Test
    void everyTeamPlays82GamesPerSeason() {
        assertEquals(2 * 1230, league.games().size());
        Map<String, Integer> perTeamSeason = new HashMap<>();
        for (var l : league.logs()) perTeamSeason.merge(l.teamId() + ":" + l.season(), 1, Integer::sum);
        assertTrue(perTeamSeason.values().stream().allMatch(n -> n == 82), perTeamSeason.toString());
    }

    @Test
    void noTeamPlaysTwiceOnTheSameDay() {
        Set<String> seen = new HashSet<>();
        for (var l : league.logs()) {
            assertTrue(seen.add(l.teamId() + "@" + l.date()), "double-booked: " + l);
        }
    }

    @Test
    void boxScoresAddUpAndMatchTheGame() {
        for (var l : league.logs()) {
            int fg2m = l.fgm() - l.fg3m();
            assertEquals(l.points(), 2 * fg2m + 3 * l.fg3m() + l.ftm(), "points mismatch: " + l);
            assertTrue(l.fgm() <= l.fga() && l.fg3m() <= l.fg3a() && l.ftm() <= l.fta(), "makes > attempts: " + l);
            assertEquals(l.won(), l.points() > l.oppPoints());
        }
        for (var g : league.games()) assertTrue(g.homeScore() != g.awayScore());
    }

    @Test
    void backToBacksAreRealisticallyRare() {
        long b2b = league.logs().stream().filter(l -> l.restDays() == 0).count();
        double perTeamSeason = b2b / (30.0 * 2);
        assertTrue(perTeamSeason >= 8 && perTeamSeason <= 22, "back-to-backs per team-season: " + perTeamSeason);
    }

    @Test
    void restDaysReflectTheSchedule() {
        Map<Integer, LocalDate> last = new HashMap<>();
        league.logs().stream().filter(l -> l.season() == 2025).forEach(l -> {
            LocalDate prev = last.put(l.teamId(), l.date());
            int expected = prev == null ? 3 : (int) Math.min(3, l.date().toEpochDay() - prev.toEpochDay() - 1);
            assertEquals(expected, l.restDays());
        });
    }
}
