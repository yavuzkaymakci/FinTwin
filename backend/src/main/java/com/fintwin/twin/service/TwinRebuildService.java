package com.fintwin.twin.service;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.twin.consumer.TwinEventListener;
import com.fintwin.twin.dto.RebuildResult;
import com.fintwin.twin.projection.TwinFeatureCalculator;
import com.fintwin.twin.projection.TwinFeatures;
import com.fintwin.twin.projection.TwinJson;
import com.fintwin.twin.projection.TwinProjector;
import com.fintwin.twin.projection.TwinState;
import com.fintwin.twin.store.EventStoreRepository;
import com.fintwin.twin.store.TwinStateRepository;
import com.fintwin.twin.store.TwinStateRepository.Snapshot;
import com.fintwin.twin.store.TwinStateRepository.StateWrite;
import com.fintwin.twin.store.TwinStateRepository.VersionedState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds twin projections from the event store with the same projector the live consumer uses.
 * VERIFY compares the result with the stored projection without writing; REPLACE pauses the consumer, discards the
 * projection (never the event store) and writes the rebuilt one.
 */
@Service
public class TwinRebuildService {

    public enum Mode {
        VERIFY, REPLACE
    }

    private static final Logger log = LoggerFactory.getLogger(TwinRebuildService.class);
    private static final int CHUNK = 250;

    private final EventStoreRepository eventStore;
    private final TwinStateRepository states;
    private final TransactionTemplate transactions;
    private final KafkaListenerEndpointRegistry listeners;

    public TwinRebuildService(EventStoreRepository eventStore, TwinStateRepository states,
                              TransactionTemplate transactions, KafkaListenerEndpointRegistry listeners) {
        this.eventStore = eventStore;
        this.states = states;
        this.transactions = transactions;
        this.listeners = listeners;
    }

    /** A customer's projection rebuilt from scratch. */
    public record Rebuilt(TwinState state, TwinFeatures features, List<Snapshot> snapshots, int events) {
    }

    public static Rebuilt rebuild(long customerId, List<CoreBankingEvent> events) {
        TwinState state = TwinProjector.newState(customerId);
        List<Snapshot> snapshots = new ArrayList<>();
        int applied = TwinProjectionService.advance(state, events, snapshots);
        TwinFeatures features = applied == 0 ? null : TwinFeatureCalculator.compute(state, state.lastEventAt);
        return new Rebuilt(state, features, snapshots, applied);
    }

    public RebuildResult run(Mode mode) {
        long t0 = System.nanoTime();
        MessageListenerContainer container = listeners.getListenerContainer(TwinEventListener.LISTENER_ID);
        boolean pause = mode == Mode.REPLACE && container != null && container.isRunning();
        if (pause) {
            container.stop();
        }
        try {
            if (mode == Mode.REPLACE) {
                transactions.executeWithoutResult(s -> states.truncateProjection());
            }
            List<Long> customers = eventStore.customerIds();
            long events = 0;
            long snapshotCount = 0;
            long stateMismatches = 0;
            long snapshotMismatches = 0;
            List<Long> samples = new ArrayList<>();
            for (int i = 0; i < customers.size(); i += CHUNK) {
                List<Long> chunk = customers.subList(i, Math.min(i + CHUNK, customers.size()));
                Map<Long, List<CoreBankingEvent>> byCustomer = eventStore.eventsOf(chunk);
                List<Rebuilt> rebuilt = new ArrayList<>(chunk.size());
                for (Long customerId : chunk) {
                    Rebuilt r = rebuild(customerId, byCustomer.getOrDefault(customerId, List.of()));
                    rebuilt.add(r);
                    events += r.events();
                    snapshotCount += r.snapshots().size();
                }
                if (mode == Mode.VERIFY) {
                    Map<Long, VersionedState> stored = states.load(chunk);
                    Map<Long, List<Snapshot>> storedSnapshots = states.snapshotsOf(chunk);
                    for (Rebuilt r : rebuilt) {
                        long id = r.state().customerId;
                        boolean stateDiffers = !sameState(r, stored.get(id));
                        boolean snapshotsDiffer = !sameSnapshots(r.snapshots(),
                                storedSnapshots.getOrDefault(id, List.of()));
                        stateMismatches += stateDiffers ? 1 : 0;
                        snapshotMismatches += snapshotsDiffer ? 1 : 0;
                        if ((stateDiffers || snapshotsDiffer) && samples.size() < 10) {
                            samples.add(id);
                        }
                    }
                } else {
                    transactions.executeWithoutResult(s -> {
                        states.save(rebuilt.stream().filter(r -> r.events() > 0)
                                .map(r -> new StateWrite(r.state(), r.features(), null)).toList());
                        states.saveSnapshots(rebuilt.stream().flatMap(r -> r.snapshots().stream()).toList());
                    });
                }
            }
            long millis = (System.nanoTime() - t0) / 1_000_000;
            RebuildResult result = new RebuildResult(mode.name(), customers.size(), events, snapshotCount,
                    stateMismatches, snapshotMismatches, samples, millis, events / Math.max(millis / 1000.0, 0.001));
            log.info("Twin rebuild {}: {}", mode, result);
            return result;
        } finally {
            if (pause) {
                container.start();
            }
        }
    }

    private static boolean sameState(Rebuilt r, VersionedState stored) {
        if (stored == null) {
            return r.events() == 0;
        }
        return stored.lastSequence() == r.state().lastSequence
                && TwinJson.tree(stored.stateJson()).equals(TwinJson.tree(TwinJson.write(r.state())))
                && TwinJson.tree(stored.featuresJson()).equals(TwinJson.tree(TwinJson.write(r.features())));
    }

    private static boolean sameSnapshots(List<Snapshot> rebuilt, List<Snapshot> stored) {
        if (rebuilt.size() != stored.size()) {
            return false;
        }
        for (int i = 0; i < rebuilt.size(); i++) {
            Snapshot a = rebuilt.get(i);
            Snapshot b = stored.get(i);
            if (!Objects.equals(a.asOf(), b.asOf()) || a.lastSequence() != b.lastSequence()
                    || !TwinJson.tree(a.stateJson()).equals(TwinJson.tree(b.stateJson()))
                    || !TwinJson.tree(a.featuresJson()).equals(TwinJson.tree(b.featuresJson()))) {
                return false;
            }
        }
        return true;
    }
}
