package com.fintwin.feed.publish;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * Simulated clock for live replay: one simulated day passes every {@code secondsPerDay} real seconds. Changing the
 * speed re-bases the clock, so simulated time never jumps.
 */
public final class ReplayClock {

    private static final double SECONDS_PER_DAY = Duration.ofDays(1).toSeconds();

    private final LongSupplier nanoTime;
    private Instant simulatedBase;
    private long realBaseNanos;
    private double secondsPerDay;

    public ReplayClock(Instant start, double secondsPerDay, LongSupplier nanoTime) {
        if (secondsPerDay <= 0) {
            throw new IllegalArgumentException("secondsPerDay must be positive");
        }
        this.nanoTime = nanoTime;
        this.simulatedBase = start;
        this.realBaseNanos = nanoTime.getAsLong();
        this.secondsPerDay = secondsPerDay;
    }

    public synchronized Instant now() {
        double realSeconds = (nanoTime.getAsLong() - realBaseNanos) / 1e9;
        double simulatedSeconds = realSeconds * SECONDS_PER_DAY / secondsPerDay;
        return simulatedBase.plusNanos(Math.round(simulatedSeconds * 1e9));
    }

    public synchronized void setSecondsPerDay(double newSecondsPerDay) {
        if (newSecondsPerDay <= 0) {
            throw new IllegalArgumentException("secondsPerDay must be positive");
        }
        Instant current = now();
        simulatedBase = current;
        realBaseNanos = nanoTime.getAsLong();
        secondsPerDay = newSecondsPerDay;
    }

    public synchronized double secondsPerDay() {
        return secondsPerDay;
    }
}
