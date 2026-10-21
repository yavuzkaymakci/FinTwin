package com.fintwin.twin.service;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.generator.GeneratorFixtures;
import com.fintwin.twin.projection.TwinJson;
import com.fintwin.twin.projection.TwinProjector;
import com.fintwin.twin.projection.TwinState;
import com.fintwin.twin.store.TwinStateRepository.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The twin must not depend on arrival order: events of one customer arrive interleaved from several topics. The
 * event store acts as a re-sequencing buffer; {@link TwinProjectionService#advance} applies only contiguous runs.
 */
class OutOfOrderProjectionTests {

    @Test
    void arbitraryArrivalOrderGivesTheSameStateAndSnapshots() {
        List<CoreBankingEvent> inOrder = SyntheticStreams.customerStream(GeneratorFixtures.simulator(3, 6), 11);
        assertThat(inOrder).hasSizeGreaterThan(100);

        TwinRebuildService.Rebuilt expected = TwinRebuildService.rebuild(inOrder.getFirst().customerId(), inOrder);

        List<CoreBankingEvent> arrival = new ArrayList<>(inOrder);
        Collections.shuffle(arrival, new Random(1));
        TwinState state = TwinProjector.newState(inOrder.getFirst().customerId());
        List<CoreBankingEvent> buffer = new ArrayList<>();
        List<Snapshot> snapshots = new ArrayList<>();
        // Deliver in batches of random size, as Kafka would across topics.
        Random sizes = new Random(2);
        for (int i = 0; i < arrival.size(); ) {
            int n = 1 + sizes.nextInt(40);
            buffer.addAll(arrival.subList(i, Math.min(i + n, arrival.size())));
            i += n;
            buffer.sort(Comparator.comparingLong(CoreBankingEvent::customerSequence));
            TwinProjectionService.advance(state, buffer, snapshots);
            buffer.removeIf(e -> e.customerSequence() <= state.lastSequence);
        }

        assertThat(buffer).isEmpty();
        assertThat(TwinJson.write(state)).isEqualTo(TwinJson.write(expected.state()));
        assertThat(snapshots).extracting(Snapshot::asOf)
                .containsExactlyElementsOf(expected.snapshots().stream().map(Snapshot::asOf).toList());
        assertThat(snapshots).extracting(Snapshot::stateJson)
                .containsExactlyElementsOf(expected.snapshots().stream().map(Snapshot::stateJson).toList());
    }

    @Test
    void writesOneSnapshotPerMonthBoundary() {
        List<CoreBankingEvent> events = SyntheticStreams.customerStream(GeneratorFixtures.simulator(3, 6), 11);
        TwinRebuildService.Rebuilt rebuilt = TwinRebuildService.rebuild(events.getFirst().customerId(), events);
        assertThat(rebuilt.snapshots()).extracting(Snapshot::asOf).doesNotHaveDuplicates()
                .allMatch(d -> d.getDayOfMonth() == 1)
                .isSortedAccordingTo(Comparator.<LocalDate>naturalOrder());
        assertThat(rebuilt.snapshots()).isNotEmpty();
    }
}
