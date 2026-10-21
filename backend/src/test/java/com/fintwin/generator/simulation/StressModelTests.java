package com.fintwin.generator.simulation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class StressModelTests {

    private static final StressModel.Drivers BASELINE =
            new StressModel.Drivers(0.15, 0.0, 0.3, 0.05, 0.0, 1.0, 0);

    private static double stressOf(StressModel.Drivers d) {
        return StressModel.sigmoid(StressModel.logit(d));
    }

    @Test
    void eachRiskDriverIncreasesStress() {
        double base = stressOf(BASELINE);
        assertThat(stressOf(new StressModel.Drivers(0.6, 0.0, 0.3, 0.05, 0.0, 1.0, 0))).isGreaterThan(base);
        assertThat(stressOf(new StressModel.Drivers(0.15, 0.2, 0.3, 0.05, 0.0, 1.0, 0))).isGreaterThan(base);
        assertThat(stressOf(new StressModel.Drivers(0.15, 0.0, 0.9, 0.05, 0.0, 1.0, 0))).isGreaterThan(base);
        assertThat(stressOf(new StressModel.Drivers(0.15, 0.0, 0.3, 0.40, 0.0, 1.0, 0))).isGreaterThan(base);
        assertThat(stressOf(new StressModel.Drivers(0.15, 0.0, 0.3, 0.05, 0.0, 1.0, 45))).isGreaterThan(base);
    }

    @Test
    void resilienceAndLiquidityReduceStress() {
        double base = stressOf(BASELINE);
        assertThat(stressOf(new StressModel.Drivers(0.15, 0.0, 0.3, 0.05, 1.5, 1.0, 0))).isLessThan(base);
        assertThat(stressOf(new StressModel.Drivers(0.15, 0.0, 0.3, 0.05, 0.0, 6.0, 0))).isLessThan(base);
    }

    @Test
    void stressIsPersistentAndBounded() {
        StressModel.Drivers distressed = new StressModel.Drivers(1.5, 0.5, 1.1, 0.8, -1.5, 0.0, 60);
        double s = 0.1;
        double next = StressModel.nextStress(s, distressed, 0);
        assertThat(next).isGreaterThan(s).isLessThan(stressOf(distressed));
        for (int i = 0; i < 50; i++) {
            s = StressModel.nextStress(s, distressed, 0);
        }
        assertThat(s).isBetween(0.0, 1.0).isCloseTo(stressOf(distressed), offset(1e-6));
    }

    @Test
    void eventProbabilitiesRiseWithStress() {
        double previousMiss = -1;
        double previousChurn = -1;
        double previousCure = 2;
        for (double stress = 0; stress <= 1.0; stress += 0.1) {
            double miss = StressModel.missProbability(stress);
            double churn = StressModel.churnHazard(stress, 0, 0, 24);
            double cure = StressModel.cureProbability(stress);
            assertThat(miss).isGreaterThan(previousMiss);
            assertThat(churn).isGreaterThan(previousChurn);
            assertThat(cure).isLessThan(previousCure);
            previousMiss = miss;
            previousChurn = churn;
            previousCure = cure;
        }
        assertThat(StressModel.missProbability(0.1)).isLessThan(0.002);
        assertThat(StressModel.missProbability(0.9)).isGreaterThan(0.2);
    }

    @Test
    void newCustomersChurnMore() {
        assertThat(StressModel.churnHazard(0.2, 0, 0, 3)).isGreaterThan(StressModel.churnHazard(0.2, 0, 0, 36));
    }
}
