package com.scoutagent.model;

import com.scoutagent.analytics.TeamDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trains the win-probability model on seasons up to {@code scout.model.last-train-season} and evaluates it on
 * every later season (out-of-sample), alongside naive baselines. Metrics are computed, never hard-coded.
 */
@Service
public class WinProbabilityService {

    private static final Logger log = LoggerFactory.getLogger(WinProbabilityService.class);

    public record Prediction(int gameId, String date, String home, String away, double homeWinProbability,
                             int homeScore, int awayScore, boolean correct) {}

    private record Trained(LogisticRegression model, FeatureEngine engine, Map<String, Object> metrics,
                           List<Prediction> recentTestPredictions) {}

    private final JdbcTemplate jdbc;
    private final TeamDirectory teams;
    private final int lastTrainSeason;
    private volatile Trained trained;

    public WinProbabilityService(JdbcTemplate jdbc, TeamDirectory teams,
                                 @Value("${scout.model.last-train-season}") int lastTrainSeason) {
        this.jdbc = jdbc;
        this.teams = teams;
        this.lastTrainSeason = lastTrainSeason;
    }

    public synchronized Map<String, Object> train() {
        long start = System.currentTimeMillis();
        List<FeatureEngine.GameRow> rows = jdbc.query("""
                SELECT g.id, g.season, g.game_date, g.home_team_id, g.away_team_id, g.home_score, g.away_score,
                       h.possessions, h.fgm, h.fg3m, h.fga, h.tov, a.fgm, a.fg3m, a.fga, a.tov
                FROM games g
                JOIN team_game_logs h ON h.game_id = g.id AND h.team_id = g.home_team_id
                JOIN team_game_logs a ON a.game_id = g.id AND a.team_id = g.away_team_id
                ORDER BY g.game_date, g.id""", (rs, i) -> new FeatureEngine.GameRow(
                rs.getInt(1), rs.getInt(2), rs.getDate(3).toLocalDate(), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                rs.getInt(7), rs.getDouble(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
                rs.getInt(13), rs.getInt(14), rs.getInt(15), rs.getInt(16)));
        if (rows.isEmpty()) throw new IllegalStateException("No games to train on");

        FeatureEngine engine = new FeatureEngine(rows);
        int firstSeason = rows.get(0).season();
        List<FeatureEngine.Example> train = new ArrayList<>(), test = new ArrayList<>();
        for (var e : engine.examples()) {
            // The first season is a warm-up for Elo and rolling features, so it's excluded from training.
            if (e.game().season() == firstSeason) continue;
            (e.game().season() <= lastTrainSeason ? train : test).add(e);
        }
        if (train.isEmpty() || test.isEmpty()) {
            throw new IllegalStateException("Need games on both sides of last-train-season=" + lastTrainSeason);
        }

        LogisticRegression model = LogisticRegression.fit(
                train.stream().map(FeatureEngine.Example::x).toList(),
                train.stream().map(FeatureEngine.Example::homeWon).toList(),
                1e-3, 2500, 0.2);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("trained_at", Instant.now().toString());
        metrics.put("train_seasons", (firstSeason + 1) + "-" + lastTrainSeason);
        metrics.put("test_seasons", (lastTrainSeason + 1) + "-" + rows.get(rows.size() - 1).season());
        metrics.put("train_games", train.size());
        metrics.put("test_games", test.size());
        metrics.put("test", evaluate(model, test));
        metrics.put("train", evaluate(model, train));
        metrics.put("per_season_test_accuracy", perSeason(model, test));
        metrics.put("calibration", calibration(model, test));
        metrics.put("coefficients", coefficients(model));

        List<Prediction> recent = test.stream()
                .sorted(Comparator.comparing((FeatureEngine.Example e) -> e.game().date()).reversed())
                .limit(25).map(e -> toPrediction(model, e)).toList();

        trained = new Trained(model, engine, metrics, recent);
        log.info("Trained win-probability model in {} ms: test metrics {}", System.currentTimeMillis() - start, metrics.get("test"));
        return metrics;
    }

    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>(current().metrics());
        m.put("recent_test_predictions", current().recentTestPredictions());
        return m;
    }

    /** Win probability for a hypothetical game between two teams based on their latest state. */
    public Map<String, Object> predict(int homeId, int awayId, int homeRest, int awayRest,
                                       double homeNetDelta, double awayNetDelta) {
        if (homeId == awayId) throw new IllegalArgumentException("A team can't play itself");
        Trained t = current();
        double[] x = t.engine().matchupFeatures(homeId, awayId, homeRest, awayRest, homeNetDelta, awayNetDelta);
        double p = t.model().predict(x);
        double[] contrib = t.model().contributions(x);

        List<Map<String, Object>> factors = new ArrayList<>();
        for (int j = 0; j < x.length; j++) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("feature", FeatureEngine.FEATURE_NAMES[j]);
            f.put("value", round(x[j], 3));
            f.put("log_odds_contribution", round(contrib[j], 3));
            factors.add(f);
        }
        factors.sort(Comparator.comparingDouble(f -> -Math.abs((double) f.get("log_odds_contribution"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("home", teams.byId(homeId).abbr());
        out.put("away", teams.byId(awayId).abbr());
        out.put("home_win_probability", round(p, 4));
        out.put("away_win_probability", round(1 - p, 4));
        out.put("home_rest_days", homeRest);
        out.put("away_rest_days", awayRest);
        if (homeNetDelta != 0 || awayNetDelta != 0) {
            out.put("scenario", Map.of("home_net_rating_delta", homeNetDelta, "away_net_rating_delta", awayNetDelta));
        }
        out.put("home_state", t.engine().teamState(homeId));
        out.put("away_state", t.engine().teamState(awayId));
        out.put("factors", factors);
        out.put("model_test_accuracy", ((Map<?, ?>) t.metrics().get("test")).get("accuracy"));
        return out;
    }

    private Trained current() {
        Trained t = trained;
        if (t == null) throw new IllegalStateException("Model is not trained yet");
        return t;
    }

    private Map<String, Object> evaluate(LogisticRegression model, List<FeatureEngine.Example> set) {
        int correct = 0, homeWins = 0, betterRecordCorrect = 0, eloCorrect = 0;
        double logLoss = 0, brier = 0;
        for (var e : set) {
            double p = model.predict(e.x());
            int y = e.homeWon();
            if ((p >= 0.5 ? 1 : 0) == y) correct++;
            homeWins += y;
            // Baseline: the team with the better record so far wins; ties go to the home team.
            int betterRecordPick = e.homeWinPctBefore() >= e.awayWinPctBefore() ? 1 : 0;
            if (betterRecordPick == y) betterRecordCorrect++;
            // Baseline: Elo alone (with home-court adjustment).
            int eloPick = e.x()[0] * 100 + FeatureEngine.ELO_HOME_ADV >= 0 ? 1 : 0;
            if (eloPick == y) eloCorrect++;
            double pc = Math.min(Math.max(p, 1e-9), 1 - 1e-9);
            logLoss -= y * Math.log(pc) + (1 - y) * Math.log(1 - pc);
            brier += (p - y) * (p - y);
        }
        int n = set.size();
        double acc = (double) correct / n;
        double homeAcc = (double) homeWins / n;
        double recordAcc = (double) betterRecordCorrect / n;
        double eloAcc = (double) eloCorrect / n;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("games", n);
        m.put("accuracy", round(acc, 4));
        m.put("log_loss", round(logLoss / n, 4));
        m.put("brier_score", round(brier / n, 4));
        Map<String, Object> baselines = new LinkedHashMap<>();
        baselines.put("always_pick_home", round(homeAcc, 4));
        baselines.put("better_record", round(recordAcc, 4));
        baselines.put("elo_only", round(eloAcc, 4));
        m.put("baseline_accuracy", baselines);
        m.put("relative_lift_vs_home_baseline_pct", round(100 * (acc - homeAcc) / homeAcc, 2));
        m.put("relative_lift_vs_record_baseline_pct", round(100 * (acc - recordAcc) / recordAcc, 2));
        return m;
    }

    private List<Map<String, Object>> perSeason(LogisticRegression model, List<FeatureEngine.Example> test) {
        Map<Integer, int[]> bySeason = new java.util.TreeMap<>();
        for (var e : test) {
            int[] c = bySeason.computeIfAbsent(e.game().season(), k -> new int[2]);
            if ((model.predict(e.x()) >= 0.5 ? 1 : 0) == e.homeWon()) c[0]++;
            c[1]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        bySeason.forEach((season, c) -> out.add(Map.of("season", season, "games", c[1], "accuracy", round((double) c[0] / c[1], 4))));
        return out;
    }

    private List<Map<String, Object>> calibration(LogisticRegression model, List<FeatureEngine.Example> test) {
        int bins = 10;
        double[] sumP = new double[bins], sumY = new double[bins];
        int[] count = new int[bins];
        for (var e : test) {
            double p = model.predict(e.x());
            int b = Math.min((int) (p * bins), bins - 1);
            sumP[b] += p;
            sumY[b] += e.homeWon();
            count[b]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int b = 0; b < bins; b++) {
            if (count[b] == 0) continue;
            out.add(Map.of("bin", b, "predicted", round(sumP[b] / count[b], 3), "actual", round(sumY[b] / count[b], 3), "games", count[b]));
        }
        return out;
    }

    private List<Map<String, Object>> coefficients(LogisticRegression model) {
        double[] w = model.weights();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("feature", "intercept", "weight", round(model.bias(), 4)));
        for (int j = 0; j < w.length; j++) out.add(Map.of("feature", FeatureEngine.FEATURE_NAMES[j], "weight", round(w[j], 4)));
        return out;
    }

    private Prediction toPrediction(LogisticRegression model, FeatureEngine.Example e) {
        double p = model.predict(e.x());
        var g = e.game();
        return new Prediction(g.gameId(), g.date().toString(), teams.byId(g.homeId()).abbr(), teams.byId(g.awayId()).abbr(),
                round(p, 4), g.homeScore(), g.awayScore(), (p >= 0.5 ? 1 : 0) == e.homeWon());
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
