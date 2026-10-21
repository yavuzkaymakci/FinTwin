package com.fintwin.twin.service;

import com.fintwin.twin.store.EventStoreRepository;
import com.fintwin.twin.store.TwinStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class TwinAdminService {

    private final EventStoreRepository eventStore;
    private final TwinStateRepository states;

    public TwinAdminService(EventStoreRepository eventStore, TwinStateRepository states) {
        this.eventStore = eventStore;
        this.states = states;
    }

    /**
     * Discards the event store and projection. Only for a regenerated core banking data set, whose events must not
     * be mixed with the old ones.
     */
    @Transactional
    public Map<String, Long> reset() {
        long events = eventStore.count();
        long twins = states.countStates();
        states.truncateProjection();
        eventStore.truncate();
        return Map.of("eventsDeleted", events, "twinsDeleted", twins);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> stats() {
        return Map.of("events", eventStore.count(), "twins", states.countStates(),
                "snapshots", states.countSnapshots());
    }
}
