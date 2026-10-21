package com.fintwin.feed.publish;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayClockTests {

    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void advancesOneSimulatedDayPerConfiguredRealSeconds() {
        AtomicLong nanos = new AtomicLong();
        ReplayClock clock = new ReplayClock(START, 5, nanos::get);
        assertThat(clock.now()).isEqualTo(START);

        nanos.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(clock.now()).isEqualTo(START.plus(Duration.ofDays(1)));

        nanos.addAndGet(Duration.ofMillis(2_500).toNanos());
        assertThat(clock.now()).isEqualTo(START.plus(Duration.ofHours(36)));
    }

    @Test
    void speedChangesDoNotMakeSimulatedTimeJump() {
        AtomicLong nanos = new AtomicLong();
        ReplayClock clock = new ReplayClock(START, 10, nanos::get);
        nanos.addAndGet(Duration.ofSeconds(10).toNanos());
        clock.setSecondsPerDay(1);
        assertThat(clock.now()).isEqualTo(START.plus(Duration.ofDays(1)));
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(clock.now()).isEqualTo(START.plus(Duration.ofDays(3)));
        assertThat(clock.secondsPerDay()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveSpeeds() {
        assertThatThrownBy(() -> new ReplayClock(START, 0, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
        ReplayClock clock = new ReplayClock(START, 1, System::nanoTime);
        assertThatThrownBy(() -> clock.setSecondsPerDay(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
