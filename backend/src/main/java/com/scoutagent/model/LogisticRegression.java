package com.scoutagent.model;

import java.util.List;

/** L2-regularized logistic regression trained by full-batch gradient descent on standardized features. */
public class LogisticRegression {

    private final double[] mean;
    private final double[] sd;
    private final double[] weights;
    private double bias;

    private LogisticRegression(int n) {
        mean = new double[n];
        sd = new double[n];
        weights = new double[n];
    }

    public static LogisticRegression fit(List<double[]> xs, List<Integer> ys, double l2, int iterations, double learningRate) {
        int n = xs.get(0).length, m = xs.size();
        LogisticRegression model = new LogisticRegression(n);
        for (double[] x : xs) for (int j = 0; j < n; j++) model.mean[j] += x[j] / m;
        for (double[] x : xs) for (int j = 0; j < n; j++) model.sd[j] += Math.pow(x[j] - model.mean[j], 2) / m;
        for (int j = 0; j < n; j++) model.sd[j] = Math.max(Math.sqrt(model.sd[j]), 1e-9);

        double[][] z = new double[m][];
        for (int i = 0; i < m; i++) z[i] = model.standardize(xs.get(i));

        for (int it = 0; it < iterations; it++) {
            double[] grad = new double[n];
            double gradBias = 0;
            for (int i = 0; i < m; i++) {
                double err = sigmoid(model.linear(z[i])) - ys.get(i);
                for (int j = 0; j < n; j++) grad[j] += err * z[i][j];
                gradBias += err;
            }
            for (int j = 0; j < n; j++) model.weights[j] -= learningRate * (grad[j] / m + l2 * model.weights[j]);
            model.bias -= learningRate * gradBias / m;
        }
        return model;
    }

    public double predict(double[] x) {
        return sigmoid(linear(standardize(x)));
    }

    /** Per-feature contribution to the log-odds (weight x standardized value). */
    public double[] contributions(double[] x) {
        double[] z = standardize(x);
        double[] c = new double[z.length];
        for (int j = 0; j < z.length; j++) c[j] = weights[j] * z[j];
        return c;
    }

    public double[] weights() {
        return weights.clone();
    }

    public double bias() {
        return bias;
    }

    private double[] standardize(double[] x) {
        double[] z = new double[x.length];
        for (int j = 0; j < x.length; j++) z[j] = (x[j] - mean[j]) / sd[j];
        return z;
    }

    private double linear(double[] z) {
        double s = bias;
        for (int j = 0; j < z.length; j++) s += weights[j] * z[j];
        return s;
    }

    static double sigmoid(double v) {
        return 1 / (1 + Math.exp(-v));
    }
}
