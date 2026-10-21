package com.fintwin.generator.profile;

import com.fintwin.customer.domain.Segment;
import com.fintwin.generator.rng.Rand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SegmentProfileTests {

    private static final int SAMPLES = 20_000;

    @ParameterizedTest
    @EnumSource(Segment.class)
    void sampledIncomesStayWithinTheSegmentRange(Segment segment) {
        SegmentProfile profile = SegmentProfile.of(segment);
        Rand r = new Rand(segment.ordinal());
        for (double cityFactor : new double[]{0.85, 1.0, 1.12}) {
            double[] incomes = IntStream.range(0, SAMPLES)
                    .mapToDouble(i -> profile.sampleBaseIncome(r, cityFactor)).toArray();
            assertThat(Arrays.stream(incomes).min().orElseThrow()).isGreaterThanOrEqualTo(profile.incomeMin());
            assertThat(Arrays.stream(incomes).max().orElseThrow()).isLessThanOrEqualTo(profile.incomeMax());
        }
    }

    @Test
    void minimumWageEarnersEarnAtLeastTheNetMinimumWage() {
        Rand r = new Rand(3);
        SegmentProfile profile = SegmentProfile.of(Segment.MIN_WAGE);
        assertThat(IntStream.range(0, SAMPLES).mapToDouble(i -> profile.sampleBaseIncome(r, 1.0)).min()
                .orElseThrow()).isGreaterThanOrEqualTo(SegmentProfile.minimumWage(2024) * 0.999);
    }

    @Test
    void medianIncomesAreOrderedAcrossSegments() {
        Map<Segment, Double> medians = new EnumMap<>(Segment.class);
        for (Segment s : Segment.values()) {
            Rand r = new Rand(11);
            double[] incomes = IntStream.range(0, SAMPLES)
                    .mapToDouble(i -> SegmentProfile.of(s).sampleBaseIncome(r, 1.0)).sorted().toArray();
            medians.put(s, incomes[SAMPLES / 2]);
        }
        assertThat(medians.get(Segment.STUDENT)).isLessThan(medians.get(Segment.RETIREE));
        assertThat(medians.get(Segment.RETIREE)).isLessThan(medians.get(Segment.WHITE_COLLAR));
        assertThat(medians.get(Segment.MIN_WAGE)).isLessThan(medians.get(Segment.WHITE_COLLAR));
    }

    @Test
    void segmentWeightsSumToOneAndDriveSampling() {
        assertThat(SegmentProfile.all().stream().mapToDouble(SegmentProfile::weight).sum()).isCloseTo(1.0,
                within(1e-9));
        Rand r = new Rand(5);
        Map<Segment, Integer> counts = new EnumMap<>(Segment.class);
        for (int i = 0; i < SAMPLES; i++) {
            counts.merge(SegmentProfile.sampleSegment(r), 1, Integer::sum);
        }
        for (SegmentProfile p : SegmentProfile.all()) {
            assertThat(counts.get(p.segment()) / (double) SAMPLES).isCloseTo(p.weight(), within(0.015));
        }
    }

    @ParameterizedTest
    @EnumSource(Segment.class)
    void agesStayWithinTheSegmentRange(Segment segment) {
        SegmentProfile profile = SegmentProfile.of(segment);
        Rand r = new Rand(9);
        IntStream.range(0, 2_000).map(i -> profile.sampleAge(r))
                .forEach(age -> assertThat(age).isBetween(profile.ageMin(), profile.ageMax()));
    }
}
