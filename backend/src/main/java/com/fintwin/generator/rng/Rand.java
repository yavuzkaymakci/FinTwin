package com.fintwin.generator.rng;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Deterministic random source with the distributions the generator needs.
 * <p>
 * Built on {@link SplittableRandom}, whose algorithm is fully specified; Gaussian and Poisson sampling are
 * implemented here (instead of relying on JDK defaults) so that output is stable across JDK versions.
 */
public final class Rand {

    private final SplittableRandom random;
    private double spareGaussian;
    private boolean hasSpareGaussian;

    public Rand(long seed) {
        this.random = new SplittableRandom(seed);
    }

    /** Uniform in [0, 1). */
    public double uniform() {
        return random.nextDouble();
    }

    /** Uniform in [min, max). */
    public double uniform(double min, double max) {
        return min + (max - min) * random.nextDouble();
    }

    /** Uniform integer in [min, max] (both inclusive). */
    public int intBetween(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    public boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** Standard normal via the Marsaglia polar method. */
    public double normal() {
        if (hasSpareGaussian) {
            hasSpareGaussian = false;
            return spareGaussian;
        }
        double u;
        double v;
        double s;
        do {
            u = random.nextDouble() * 2 - 1;
            v = random.nextDouble() * 2 - 1;
            s = u * u + v * v;
        } while (s >= 1 || s == 0);
        double factor = Math.sqrt(-2 * Math.log(s) / s);
        spareGaussian = v * factor;
        hasSpareGaussian = true;
        return u * factor;
    }

    public double normal(double mean, double sd) {
        return mean + sd * normal();
    }

    /** Lognormal parameterised by its median. */
    public double lognormalMedian(double median, double sigma) {
        return median * Math.exp(sigma * normal());
    }

    /** Lognormal parameterised by its mean (mean-preserving spread). */
    public double lognormalMean(double mean, double sigma) {
        return mean * Math.exp(sigma * normal() - sigma * sigma / 2);
    }

    public int poisson(double lambda) {
        if (lambda <= 0) {
            return 0;
        }
        if (lambda > 30) {
            return Math.max(0, (int) Math.round(normal(lambda, Math.sqrt(lambda))));
        }
        double limit = Math.exp(-lambda);
        double product = random.nextDouble();
        int count = 0;
        while (product > limit) {
            product *= random.nextDouble();
            count++;
        }
        return count;
    }

    /** Geometric number of trials until first success (>= 1) with the given mean. */
    public int geometric(double mean) {
        double p = 1.0 / Math.max(mean, 1.0);
        return 1 + (int) Math.floor(Math.log(1 - random.nextDouble()) / Math.log(1 - p + 1e-12));
    }

    public <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    public <T> T pick(T[] items) {
        return items[random.nextInt(items.length)];
    }

    /** Index drawn proportionally to non-negative {@code weights}. */
    public int pickWeighted(double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double target = random.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (target < cumulative) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /** Zipf-like index in [0, n): rank r drawn with weight 1 / (r + 1)^exponent. */
    public int zipf(int n, double exponent) {
        double[] weights = new double[n];
        for (int i = 0; i < n; i++) {
            weights[i] = 1.0 / Math.pow(i + 1, exponent);
        }
        return pickWeighted(weights);
    }

    /** Random digits, e.g. terminal or reference numbers. */
    public String digits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        return sb.toString();
    }
}
