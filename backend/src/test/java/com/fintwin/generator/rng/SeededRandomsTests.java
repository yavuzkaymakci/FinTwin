package com.fintwin.generator.rng;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SeededRandomsTests {

    @Test
    void sameSeedAndIndexGiveTheSameStream() {
        Rand a = SeededRandoms.forCustomer(42, 7);
        Rand b = SeededRandoms.forCustomer(42, 7);
        for (int i = 0; i < 1_000; i++) {
            assertThat(a.uniform()).isEqualTo(b.uniform());
            assertThat(a.normal()).isEqualTo(b.normal());
        }
    }

    @Test
    void differentIndicesAndSeedsGiveDifferentStreams() {
        double first = SeededRandoms.forCustomer(42, 7).uniform();
        assertThat(SeededRandoms.forCustomer(42, 8).uniform()).isNotEqualTo(first);
        assertThat(SeededRandoms.forCustomer(43, 7).uniform()).isNotEqualTo(first);
        assertThat(SeededRandoms.forStream(42, SeededRandoms.Stream.MERCHANT_CATALOG, 7).uniform())
                .isNotEqualTo(first);
    }

    @Test
    void distributionsHaveTheExpectedMoments() {
        Rand r = new Rand(1);
        int n = 200_000;
        double[] normals = IntStream.range(0, n).mapToDouble(i -> r.normal()).toArray();
        double mean = Arrays.stream(normals).average().orElseThrow();
        double variance = Arrays.stream(normals).map(x -> (x - mean) * (x - mean)).average().orElseThrow();
        assertThat(mean).isCloseTo(0, within(0.01));
        assertThat(variance).isCloseTo(1, within(0.02));

        double poissonMean = IntStream.range(0, n).map(i -> r.poisson(3.5)).average().orElseThrow();
        assertThat(poissonMean).isCloseTo(3.5, within(0.03));

        double lognormalMean = IntStream.range(0, n).mapToDouble(i -> r.lognormalMean(100, 0.6)).average()
                .orElseThrow();
        assertThat(lognormalMean).isCloseTo(100, within(1.5));
    }
}
