package com.fintwin.twin.service;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.twin.projection.TwinFeatureCalculator;
import com.fintwin.twin.projection.TwinFeatures;
import com.fintwin.twin.projection.TwinJson;
import com.fintwin.twin.projection.TwinProjector;
import com.fintwin.twin.projection.TwinState;
import com.fintwin.twin.store.EventStoreRepository;
import com.fintwin.twin.store.StoredEvent;
import com.fintwin.twin.store.TwinStateRepository;
import com.fintwin.twin.store.TwinStateRepository.Snapshot;
import com.fintwin.twin.store.TwinStateRepository.StateWrite;
import com.fintwin.twin.store.TwinStateRepository.VersionedState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Event sourcing core of the twin. For each batch, in one database transaction: append to the event store
 * (deduplicating by event id), then advance every affected customer's projection through the contiguous run of
 * stored-but-unapplied events in sequence order, writing month-start snapshots on the way.
 */
@Service
public class TwinProjectionService {

    /** Outcome of one batch. */
    public record BatchResult(int received, int appended, int duplicates, int applied, int customersUpdated,
                              int snapshots) {
    }

    private final EventStoreRepository eventStore;
    private final TwinStateRepository states;
    private final Counter received;
    private final Counter duplicates;
    private final Counter applied;
    private final Counter snapshotsWritten;

    public TwinProjectionService(EventStoreRepository eventStore, TwinStateRepository states,
                                 MeterRegistry registry) {
        this.eventStore = eventStore;
        this.states = states;
        this.received = Counter.builder("twin.events.received").description("Events received from Kafka")
                .register(registry);
        this.duplicates = Counter.builder("twin.events.duplicates")
                .description("Redelivered events ignored by event id").register(registry);
        this.applied = Counter.builder("twin.events.processed")
                .description("Events applied to twin projections").register(registry);
        this.snapshotsWritten = Counter.builder("twin.snapshots.written").register(registry);
    }

    @Transactional
    public BatchResult process(List<StoredEvent> batch) {
        Set<UUID> inserted = eventStore.append(batch);
        Set<Long> customers = new LinkedHashSet<>();
        for (StoredEvent e : batch) {
            customers.add(e.event().customerId());
        }
        Map<Long, VersionedState> loaded = states.load(customers);
        Map<Long, List<CoreBankingEvent>> pending = eventStore.unapplied(customers);

        List<StateWrite> writes = new ArrayList<>();
        List<Snapshot> snapshots = new ArrayList<>();
        int appliedCount = 0;
        for (Map.Entry<Long, List<CoreBankingEvent>> entry : pending.entrySet()) {
            VersionedState current = loaded.get(entry.getKey());
            TwinState state = current == null ? TwinProjector.newState(entry.getKey()) : current.state();
            int n = advance(state, entry.getValue(), snapshots);
            if (n > 0) {
                appliedCount += n;
                writes.add(new StateWrite(state, TwinFeatureCalculator.compute(state, state.lastEventAt),
                        current == null ? null : current.version()));
            }
        }
        states.save(writes);
        states.saveSnapshots(snapshots);

        int duplicateCount = batch.size() - inserted.size();
        received.increment(batch.size());
        duplicates.increment(duplicateCount);
        applied.increment(appliedCount);
        snapshotsWritten.increment(snapshots.size());
        return new BatchResult(batch.size(), inserted.size(), duplicateCount, appliedCount, writes.size(),
                snapshots.size());
    }

    /**
     * Applies the contiguous prefix of {@code events} (sorted by sequence) that continues the state's sequence;
     * stops at the first gap, leaving later events in the store until the missing one arrives.
     *
     * @return number of events applied
     */
    public static int advance(TwinState state, List<CoreBankingEvent> events, List<Snapshot> snapshots) {
        int count = 0;
        for (CoreBankingEvent event : events) {
            if (event.customerSequence() <= state.lastSequence) {
                continue;
            }
            if (event.customerSequence() != state.lastSequence + 1) {
                break;
            }
            snapshotMonthBoundaries(state, event, snapshots);
            TwinProjector.apply(state, event);
            count++;
        }
        return count;
    }

    /**
     * Before the first event of a new month, records the state as of the start of every month boundary crossed
     * since the previous event (features evaluated at that month start).
     */
    static void snapshotMonthBoundaries(TwinState state, CoreBankingEvent next, List<Snapshot> snapshots) {
        if (state.currentMonth == null) {
            return;
        }
        YearMonth nextMonth = YearMonth.from(TwinProjector.localDate(next.occurredAt()));
        String stateJson = null;
        for (YearMonth m = YearMonth.parse(state.currentMonth).plusMonths(1); !m.isAfter(nextMonth);
             m = m.plusMonths(1)) {
            if (stateJson == null) {
                stateJson = TwinJson.write(state);
            }
            TwinFeatures features = TwinFeatureCalculator.compute(state,
                    m.atDay(1).atStartOfDay(TwinProjector.BANK_ZONE).toInstant());
            snapshots.add(new Snapshot(state.customerId, m.atDay(1), state.lastSequence, stateJson,
                    TwinJson.write(features)));
        }
    }
}
