package com.fintwin.twin.service;

import com.fintwin.twin.dto.TwinLiveView;
import com.fintwin.twin.store.EventStoreRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The live view reports the consumer counters and caches the expensive event-store count. */
class TwinLiveServiceTests {

    @Test
    void reportsCountersRecentEventsAndCachesTheCount() {
        EventStoreRepository store = mock(EventStoreRepository.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Counter.builder("twin.events.received").register(meters).increment(12);
        Counter.builder("twin.events.processed").register(meters).increment(10);
        Counter.builder("twin.events.duplicates").register(meters).increment(2);
        var event = new EventStoreRepository.RecentEvent(7, 42, "TransactionRecorded", Instant.now(), Instant.now(),
                "125.50", "TRY", "DEBIT", "KARDESLER MKT");
        given(store.count()).willReturn(1_000L);
        given(store.recent(5)).willReturn(List.of(event));
        TwinLiveService service = new TwinLiveService(store, meters);

        TwinLiveView view = service.live(5);
        service.live(5);

        assertThat(view.received()).isEqualTo(12);
        assertThat(view.processed()).isEqualTo(10);
        assertThat(view.duplicates()).isEqualTo(2);
        assertThat(view.deadLettered()).isZero();
        assertThat(view.storedEvents()).isEqualTo(1_000);
        assertThat(view.recent()).containsExactly(event);
        verify(store, times(1)).count();
    }
}
