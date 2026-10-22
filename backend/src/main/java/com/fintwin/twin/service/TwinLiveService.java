package com.fintwin.twin.service;

import com.fintwin.twin.dto.TwinLiveView;
import com.fintwin.twin.store.EventStoreRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** What the twin consumer has received and applied, for the live feed view. */
@Service
public class TwinLiveService {

    private static final Duration COUNT_CACHE = Duration.ofSeconds(30);

    private final EventStoreRepository eventStore;
    private final MeterRegistry meters;
    private volatile long storedEvents = -1;
    private volatile Instant countedAt = Instant.EPOCH;

    public TwinLiveService(EventStoreRepository eventStore, MeterRegistry meters) {
        this.eventStore = eventStore;
        this.meters = meters;
    }

    public TwinLiveView live(int limit) {
        Instant now = Instant.now();
        if (storedEvents < 0 || countedAt.plus(COUNT_CACHE).isBefore(now)) {
            storedEvents = eventStore.count();  // exact count of millions of rows: cached
            countedAt = now;
        }
        return new TwinLiveView(storedEvents, countedAt, counter("twin.events.received"),
                counter("twin.events.processed"), counter("twin.events.duplicates"), counter("twin.events.dlt"),
                eventStore.recent(limit));
    }

    private long counter(String name) {
        Counter c = meters.find(name).counter();
        return c == null ? 0 : (long) c.count();
    }
}
