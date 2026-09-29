package com.scoutagent.model;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays games in chronological order and produces, for each game, features computed strictly from information
 * available before tip-off (no leakage). After the replay, {@link #matchupFeatures} describes a hypothetical game
 * using each team's latest state.
 */
public class FeatureEngine {

    public static final String[] FEATURE_NAMES = {
            "elo_diff", "season_net_rating_diff", "last10_net_rating_diff", "season_win_pct_diff",
            "rest_days_diff", "home_back_to_back", "away_back_to_back", "season_efg_diff", "season_tov_pct_diff",
    };

    /** One completed game with the box aggregates the engine needs. */
    public record GameRow(int gameId, int season, LocalDate date, int homeId, int awayId, int homeScore, int awayScore,
                          double possessions, int homeFgm, int homeFg3m, int homeFga, int homeTov,
                          int awayFgm, int awayFg3m, int awayFga, int awayTov) {}

    public record Example(GameRow game, double[] x, int homeWon, double homeWinPctBefore, double awayWinPctBefore) {}

    static final double ELO_HOME_ADV = 70;
    static final double ELO_K = 20;

    static final class TeamState {
        double elo = 1500;
        int season = -1;
        int games, wins;
        double pts, oppPts, poss, fgm, fg3m, fga, tov;
        final Deque<Double> last10 = new ArrayDeque<>();
        LocalDate lastPlayed;
        /** Early-season net rating shrinks toward this (half of last season's), not toward zero. */
        double priorNet;

        void newSeason(int s) {
            priorNet = poss == 0 ? 0 : 0.5 * 100 * (pts - oppPts) / poss;
            season = s;
            elo = 0.75 * elo + 0.25 * 1500;
            games = wins = 0;
            pts = oppPts = poss = fgm = fg3m = fga = tov = 0;
            last10.clear();
            lastPlayed = null;
        }

        double netRating() {
            double raw = poss == 0 ? 0 : 100 * (pts - oppPts) / poss;
            return (raw * games + priorNet * 10) / (games + 10.0);
        }
        double last10Net() {
            if (last10.isEmpty()) return 0;
            double avg = last10.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            return avg * last10.size() / (last10.size() + 3.0);
        }
        double winPct() { return (wins + 2.5) / (games + 5.0); }
        double rawWinPct() { return games == 0 ? 0.5 : (double) wins / games; }
        double efg() { return fga == 0 ? 0.53 : (fgm + 0.5 * fg3m) / fga; }
        double tovPct() { return poss == 0 ? 0.13 : tov / poss; }
        int restDays(LocalDate date) {
            return lastPlayed == null ? 3 : (int) Math.min(3, date.toEpochDay() - lastPlayed.toEpochDay() - 1);
        }
    }

    private final Map<Integer, TeamState> states = new HashMap<>();
    private final List<Example> examples = new ArrayList<>();

    public FeatureEngine(List<GameRow> gamesInDateOrder) {
        for (GameRow g : gamesInDateOrder) {
            TeamState h = state(g.homeId(), g.season());
            TeamState a = state(g.awayId(), g.season());
            double[] x = features(h, a, h.restDays(g.date()), a.restDays(g.date()), 0, 0);
            examples.add(new Example(g, x, g.homeScore() > g.awayScore() ? 1 : 0, h.rawWinPct(), a.rawWinPct()));
            update(h, a, g);
        }
    }

    public List<Example> examples() {
        return examples;
    }

    /**
     * Features for a hypothetical game using each team's latest state. The net-rating deltas let callers ask
     * "what if this team plays N points per 100 possessions better than its baseline?".
     */
    public double[] matchupFeatures(int homeId, int awayId, int homeRest, int awayRest,
                                    double homeNetDelta, double awayNetDelta) {
        TeamState h = states.get(homeId), a = states.get(awayId);
        if (h == null || a == null) throw new IllegalArgumentException("Team has no games on record");
        return features(h, a, homeRest, awayRest, homeNetDelta, awayNetDelta);
    }

    public Map<String, Double> teamState(int teamId) {
        TeamState s = states.get(teamId);
        return Map.of("elo", round(s.elo), "season_net_rating", round(s.netRating()),
                "last10_net_rating", round(s.last10Net()), "win_pct", round(s.rawWinPct()));
    }

    private static double[] features(TeamState h, TeamState a, int homeRest, int awayRest,
                                     double homeNetDelta, double awayNetDelta) {
        double netDelta = homeNetDelta - awayNetDelta;
        return new double[]{
                (h.elo - a.elo) / 100.0,
                h.netRating() - a.netRating() + netDelta,
                h.last10Net() - a.last10Net() + netDelta,
                h.winPct() - a.winPct(),
                Math.min(homeRest, 3) - Math.min(awayRest, 3),
                homeRest == 0 ? 1 : 0,
                awayRest == 0 ? 1 : 0,
                (h.efg() - a.efg()) * 100,
                (h.tovPct() - a.tovPct()) * 100,
        };
    }

    private TeamState state(int teamId, int season) {
        TeamState s = states.computeIfAbsent(teamId, k -> new TeamState());
        if (s.season != season) s.newSeason(season);
        return s;
    }

    private static void update(TeamState h, TeamState a, GameRow g) {
        int margin = g.homeScore() - g.awayScore();
        double expectedHome = 1 / (1 + Math.pow(10, -(h.elo + ELO_HOME_ADV - a.elo) / 400));
        double actual = margin > 0 ? 1 : 0;
        double winnerEloDiff = margin > 0 ? h.elo + ELO_HOME_ADV - a.elo : a.elo - h.elo - ELO_HOME_ADV;
        double movMult = Math.log(Math.abs(margin) + 1) * 2.2 / (winnerEloDiff * 0.001 + 2.2);
        double delta = ELO_K * movMult * (actual - expectedHome);
        h.elo += delta;
        a.elo -= delta;

        apply(h, g.homeScore(), g.awayScore(), g.possessions(), g.homeFgm(), g.homeFg3m(), g.homeFga(), g.homeTov(), g.date());
        apply(a, g.awayScore(), g.homeScore(), g.possessions(), g.awayFgm(), g.awayFg3m(), g.awayFga(), g.awayTov(), g.date());
    }

    private static void apply(TeamState s, int pts, int opp, double poss, int fgm, int fg3m, int fga, int tov, LocalDate date) {
        s.games++;
        if (pts > opp) s.wins++;
        s.pts += pts;
        s.oppPts += opp;
        s.poss += poss;
        s.fgm += fgm;
        s.fg3m += fg3m;
        s.fga += fga;
        s.tov += tov;
        s.last10.addLast(100.0 * (pts - opp) / poss);
        if (s.last10.size() > 10) s.last10.removeFirst();
        s.lastPlayed = date;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
