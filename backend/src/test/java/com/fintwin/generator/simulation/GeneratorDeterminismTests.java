package com.fintwin.generator.simulation;

import com.fintwin.generator.GeneratorFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorDeterminismTests {

    private static List<Sim.CustomerHistory> run(long seed, int customers, int months, boolean parallel) {
        CustomerSimulator simulator = GeneratorFixtures.simulator(seed, months);
        IntStream indices = IntStream.range(0, customers);
        return (parallel ? indices.parallel() : indices).mapToObj(simulator::simulate).toList();
    }

    @Test
    void sameSeedProducesIdenticalHistories() {
        String first = GeneratorFixtures.fingerprint(run(42, 40, 12, false));
        String second = GeneratorFixtures.fingerprint(run(42, 40, 12, false));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void outputDoesNotDependOnThreading() {
        assertThat(GeneratorFixtures.fingerprint(run(42, 40, 12, true)))
                .isEqualTo(GeneratorFixtures.fingerprint(run(42, 40, 12, false)));
    }

    @Test
    void customerHistoryDoesNotDependOnPortfolioSize() {
        Sim.CustomerHistory alone = GeneratorFixtures.simulator(42, 12).simulate(17);
        Sim.CustomerHistory inPortfolio = run(42, 40, 12, false).get(17);
        assertThat(GeneratorFixtures.fingerprint(List.of(alone)))
                .isEqualTo(GeneratorFixtures.fingerprint(List.of(inPortfolio)));
    }

    @Test
    void differentSeedProducesDifferentHistories() {
        assertThat(GeneratorFixtures.fingerprint(run(43, 40, 12, false)))
                .isNotEqualTo(GeneratorFixtures.fingerprint(run(42, 40, 12, false)));
    }
}
