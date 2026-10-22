package com.fintwin.twin.service;

import com.fintwin.twin.dto.TwinLiveView;
import com.fintwin.twin.store.EventStoreRepository;
import com.fintwin.twin.store.TwinStateRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** What the twin consumer has received and applied, for the live feed view. */
@Service
public class TwinLiveService {

    private final EventStoreRepository eventStore;
    private final TwinStateRepository states;
    private final MeterRegistry meters;

    public TwinLiveService(EventStoreRepository eventStore, TwinStateRepository states, MeterRegistry meters) {
        this.eventStore = eventStore;
        this.states = states;
        this.meters = meters;
    }

    public TwinLiveView live(int limit) {
        // sum of the per-customer sequences instead of COUNT(*) over millions of event-store rows
        long stored = states.appliedEvents();
        return new TwinLiveView(stored, Instant.now(), counter("twin.events.received"),
                counter("twin.events.processed"), counter("twin.events.duplicates"), counter("twin.events.dlt"),
                eventStore.recent(limit));
    }

    private long counter(String name) {
        Counter c = meters.find(name).counter();
        return c == null ? 0 : (long) c.count();
    }
}
