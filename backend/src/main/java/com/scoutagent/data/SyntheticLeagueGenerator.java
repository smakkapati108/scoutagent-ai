package com.scoutagent.data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates a synthetic basketball league: 30 teams whose latent strengths drift within and across seasons,
 * a realistic schedule (rest days and back-to-backs emerge from it), and box scores that are driven by
 * those strengths plus game-level noise. The data is fake, but its structure is realistic enough that
 * predictive models and scouting tools behave the way they would on real game logs.
 */
public class SyntheticLeagueGenerator {

    public record Team(int id, String abbr, String name, String conference) {}

    public record Game(int id, int season, LocalDate date, int homeId, int awayId,
                       int homeScore, int awayScore, boolean overtime) {}

    public record Log(int gameId, int teamId, int opponentId, int season, LocalDate date, boolean home,
                      int restDays, boolean won, int points, int oppPoints, double possessions,
                      int fgm, int fga, int fg3m, int fg3a, int ftm, int fta,
                      int oreb, int dreb, int ast, int tov, int stl, int blk) {}

    public record League(List<Team> teams, List<Game> games, List<Log> logs) {}

    private static final String[][] TEAMS = {
            {"BOS", "Boston Harbormen", "East"}, {"NYK", "New York Knights", "East"}, {"PHI", "Philadelphia Founders", "East"},
            {"BKN", "Brooklyn Bridges", "East"}, {"TOR", "Toronto Northmen", "East"}, {"CHI", "Chicago Gales", "East"},
            {"CLE", "Cleveland Forge", "East"}, {"DET", "Detroit Pistons", "East"}, {"IND", "Indiana Racers", "East"},
            {"MIL", "Milwaukee Stags", "East"}, {"ATL", "Atlanta Firebirds", "East"}, {"CHA", "Charlotte Hornets", "East"},
            {"MIA", "Miami Tide", "East"}, {"ORL", "Orlando Comets", "East"}, {"WAS", "Washington Senators", "East"},
            {"DEN", "Denver Summit", "West"}, {"MIN", "Minnesota Timber", "West"}, {"OKC", "Oklahoma City Storm", "West"},
            {"POR", "Portland Pioneers", "West"}, {"UTA", "Utah Peaks", "West"}, {"GSW", "Golden State Bay", "West"},
            {"LAC", "Los Angeles Clippers", "West"}, {"LAL", "Los Angeles Stars", "West"}, {"PHX", "Phoenix Flares", "West"},
            {"SAC", "Sacramento Monarchs", "West"}, {"DAL", "Dallas Mavens", "West"}, {"HOU", "Houston Orbit", "West"},
            {"MEM", "Memphis Blues", "West"}, {"NOP", "New Orleans Brass", "West"}, {"SAS", "San Antonio Missions", "West"},
    };

    /** Latent, evolving team profile. Ratings are points per 100 possessions relative to league average. */
    private static final class Profile {
        double off, def, pace, threeRate, tovRate, orebRate, ftRate;
    }

    private final Random rng;

    public SyntheticLeagueGenerator(long seed) {
        this.rng = new Random(seed);
    }

    public League generate(int firstSeason, int lastSeason) {
        List<Team> teams = new ArrayList<>();
        for (int i = 0; i < TEAMS.length; i++) {
            teams.add(new Team(i + 1, TEAMS[i][0], TEAMS[i][1], TEAMS[i][2]));
        }

        Map<Integer, Profile> profiles = new HashMap<>();
        for (Team t : teams) {
            Profile p = new Profile();
            p.off = gauss(0, 4.0);
            p.def = gauss(0, 4.0);
            p.pace = gauss(99, 2.5);
            p.threeRate = clamp(gauss(0.38, 0.04), 0.28, 0.50);
            p.tovRate = clamp(gauss(0.13, 0.012), 0.10, 0.17);
            p.orebRate = clamp(gauss(0.25, 0.025), 0.18, 0.33);
            p.ftRate = clamp(gauss(0.26, 0.03), 0.18, 0.36);
            profiles.put(t.id(), p);
        }

        List<Game> games = new ArrayList<>();
        List<Log> logs = new ArrayList<>();
        int gameId = 1;

        for (int season = firstSeason; season <= lastSeason; season++) {
            // Off-season: regress toward the mean and apply roster turnover.
            for (Profile p : profiles.values()) {
                p.off = 0.65 * p.off + gauss(0, 2.8);
                p.def = 0.65 * p.def + gauss(0, 2.8);
                p.pace = 0.7 * p.pace + 0.3 * 99 + gauss(0, 1.2) + 0.25; // league slowly speeds up
                p.threeRate = clamp(p.threeRate + gauss(0.004, 0.015), 0.28, 0.52);
            }

            Map<Integer, LocalDate> lastPlayed = new HashMap<>();
            List<int[]> schedule = buildSchedule(teams.size());
            LocalDate day = LocalDate.of(season - 1, 10, 22);
            List<int[]> pending = new ArrayList<>(schedule);

            while (!pending.isEmpty()) {
                boolean[] busy = new boolean[teams.size() + 1];
                // Schedulers avoid back-to-backs: each day, a team that played yesterday is available only rarely.
                boolean[] tired = new boolean[teams.size() + 1];
                for (int t = 1; t <= teams.size(); t++) {
                    tired[t] = day.minusDays(1).equals(lastPlayed.get(t)) && rng.nextDouble() < 0.8;
                }
                int todays = 0;
                int maxToday = 4 + rng.nextInt(8);
                var it = pending.iterator();
                while (it.hasNext() && todays < maxToday) {
                    int[] m = it.next();
                    if (busy[m[0]] || busy[m[1]] || tired[m[0]] || tired[m[1]]) continue;
                    busy[m[0]] = busy[m[1]] = true;
                    it.remove();
                    todays++;

                    int home = m[0], away = m[1];
                    int homeRest = restDays(lastPlayed.get(home), day);
                    int awayRest = restDays(lastPlayed.get(away), day);
                    lastPlayed.put(home, day);
                    lastPlayed.put(away, day);

                    Profile hp = profiles.get(home), ap = profiles.get(away);
                    double poss = (hp.pace + ap.pace) / 2 + gauss(0, 3.0);
                    double homeEdge = 1.4;
                    double hOffPer100 = 112 + hp.off - ap.def + homeEdge + fatigue(homeRest) + gauss(0, 8.5);
                    double aOffPer100 = 112 + ap.off - hp.def - homeEdge + fatigue(awayRest) + gauss(0, 8.5);

                    int[] hBox = boxScore(hp, ap, poss, hOffPer100);
                    int[] aBox = boxScore(ap, hp, poss, aOffPer100);
                    int hPts = hBox[0], aPts = aBox[0];
                    boolean ot = false;
                    while (hPts == aPts) {
                        ot = true;
                        int hOt = 8 + rng.nextInt(9), aOt = 8 + rng.nextInt(9);
                        hPts += hOt;
                        aPts += aOt;
                        addOvertime(hBox, hOt);
                        addOvertime(aBox, aOt);
                    }
                    hBox[0] = hPts;
                    aBox[0] = aPts;

                    games.add(new Game(gameId, season, day, home, away, hPts, aPts, ot));
                    logs.add(toLog(gameId, home, away, season, day, true, homeRest, hBox, aBox, poss));
                    logs.add(toLog(gameId, away, home, season, day, false, awayRest, aBox, hBox, poss));
                    gameId++;

                    // In-season drift: injuries, development, trades.
                    hp.off += gauss(0, 0.12); hp.def += gauss(0, 0.12);
                    ap.off += gauss(0, 0.12); ap.def += gauss(0, 0.12);
                }
                day = day.plusDays(1);
            }
        }
        return new League(teams, games, logs);
    }

    /** Each pair plays home-and-away, plus extra games to reach 82 per team (1,230 games). */
    private List<int[]> buildSchedule(int n) {
        List<int[]> matchups = new ArrayList<>();
        for (int a = 1; a <= n; a++) {
            for (int b = a + 1; b <= n; b++) {
                matchups.add(new int[]{a, b});
                matchups.add(new int[]{b, a});
            }
        }
        // 24 more games per team: 12 rounds of a randomized perfect matching, alternating home.
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) ids.add(i);
        for (int round = 0; round < 12; round++) {
            Collections.shuffle(ids, rng);
            for (int i = 0; i < n; i += 2) {
                matchups.add(new int[]{ids.get(i), ids.get(i + 1)});
                matchups.add(new int[]{ids.get(i + 1), ids.get(i)});
            }
        }
        Collections.shuffle(matchups, rng);
        return matchups;
    }

    /** Returns {points, fgm, fga, fg3m, fg3a, ftm, fta, oreb, dreb, ast, tov, stl, blk}. */
    private int[] boxScore(Profile team, Profile opp, double poss, double offPer100) {
        int tov = (int) Math.round(poss * clamp(team.tovRate + gauss(0, 0.02), 0.06, 0.22));
        int fta = (int) Math.round(poss * clamp(team.ftRate + gauss(0, 0.05), 0.08, 0.5));
        double ftPct = clamp(gauss(0.78, 0.06), 0.55, 0.95);
        int ftm = (int) Math.round(fta * ftPct);
        int fga = (int) Math.round(poss - tov - 0.44 * fta + poss * team.orebRate * 0.45);
        fga = Math.max(fga, 60);
        int fg3a = (int) Math.round(fga * clamp(team.threeRate + gauss(0, 0.04), 0.2, 0.6));
        int fg2a = fga - fg3a;

        int targetPts = (int) Math.round(poss * offPer100 / 100);
        // Split the remaining (non free throw) points between twos and threes with shooting luck.
        double threePct = clamp(gauss(0.36, 0.06), 0.15, 0.6);
        int fg3m = (int) Math.round(fg3a * threePct);
        int fg2m = (int) Math.round((targetPts - ftm - 3.0 * fg3m) / 2.0);
        fg2m = (int) clamp(fg2m, Math.round(fg2a * 0.35), Math.round(fg2a * 0.70));
        int points = 2 * fg2m + 3 * fg3m + ftm;

        int fgm = fg2m + fg3m;
        int misses = fga - fgm;
        int oreb = (int) Math.round(misses * clamp(team.orebRate + gauss(0, 0.05), 0.08, 0.45));
        int dreb = (int) Math.round(34 + gauss(0, 4) - opp.orebRate * 10);
        int ast = (int) Math.round(fgm * clamp(gauss(0.60, 0.06), 0.4, 0.8));
        int stl = (int) Math.round(clamp(gauss(7.5, 2.5), 1, 18));
        int blk = (int) Math.round(clamp(gauss(5, 2), 0, 14));
        return new int[]{points, fgm, fga, fg3m, fg3a, ftm, fta, oreb, dreb, ast, tov, stl, blk};
    }

    /** Credits overtime points as two-point makes plus a free throw, keeping the box score consistent. */
    private static void addOvertime(int[] box, int points) {
        box[1] += points / 2;
        box[2] += points / 2 + 4;
        box[5] += points % 2;
        box[6] += points % 2 + 1;
    }

    private Log toLog(int gameId, int team, int opp, int season, LocalDate date, boolean home, int rest,
                      int[] b, int[] ob, double poss) {
        return new Log(gameId, team, opp, season, date, home, rest, b[0] > ob[0], b[0], ob[0],
                Math.round(poss * 10) / 10.0,
                b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12]);
    }

    private static int restDays(LocalDate last, LocalDate today) {
        if (last == null) return 3;
        return (int) Math.min(3, today.toEpochDay() - last.toEpochDay() - 1);
    }

    private static double fatigue(int restDays) {
        return restDays == 0 ? -2.0 : restDays >= 2 ? 0.6 : 0.0;
    }

    private double gauss(double mean, double sd) {
        return mean + rng.nextGaussian() * sd;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
