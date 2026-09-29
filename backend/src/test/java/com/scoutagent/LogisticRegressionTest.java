package com.scoutagent;

import com.scoutagent.model.LogisticRegression;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticRegressionTest {

    @Test
    void recoversAKnownRelationship() {
        Random rng = new Random(1);
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            double a = rng.nextGaussian(), noise = rng.nextGaussian();
            double p = 1 / (1 + Math.exp(-(0.5 + 2 * a)));
            xs.add(new double[]{a, noise});
            ys.add(rng.nextDouble() < p ? 1 : 0);
        }
        LogisticRegression m = LogisticRegression.fit(xs, ys, 1e-4, 3000, 0.3);
        double[] w = m.weights();
        assertTrue(w[0] > 1.5 && w[0] < 2.5, "signal weight " + w[0]);
        assertTrue(Math.abs(w[1]) < 0.15, "noise weight " + w[1]);
        assertEquals(1 / (1 + Math.exp(-0.5)), m.predict(new double[]{0, 0}), 0.05);
    }
}
