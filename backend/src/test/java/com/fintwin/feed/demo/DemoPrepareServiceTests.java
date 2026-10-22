package com.fintwin.feed.demo;

import com.fintwin.feed.dto.ReplayStatus;
import com.fintwin.feed.publish.ReplayService;
import com.fintwin.twin.service.TwinAdminService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** The demo preparation resets the twin, rewinds the cursor and bulk-replays all but the last days. */
class DemoPrepareServiceTests {

    private final ReplayService replay = mock(ReplayService.class);
    private final TwinAdminService twins = mock(TwinAdminService.class);
    private final DemoPrepareService service = new DemoPrepareService(replay, twins);

    private static ReplayStatus status(String mode) {
        // Feed ends 2026-09-30 23:59 Istanbul time.
        return new ReplayStatus(mode, "epoch", Instant.parse("2024-10-01T00:00:00Z"),
                Instant.parse("2026-09-30T20:59:00Z"), null, null, 0, 100, 100, 0, 0, null, null);
    }

    @Test
    void replaysEverythingButTheLastDays() {
        given(replay.status()).willReturn(status("IDLE"));
        given(twins.reset()).willReturn(Map.of("eventsDeleted", 10L));
        DemoPrepareService.DemoPreparation result = service.prepare(3);
        assertThat(result.replayedUntil()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(result.liveFrom()).isEqualTo(LocalDate.of(2026, 9, 28));
        InOrder order = inOrder(twins, replay);
        order.verify(twins).reset();
        order.verify(replay).reset();
        order.verify(replay).startBulk(LocalDate.of(2026, 9, 27));
    }

    @Test
    void refusesWhileAReplayIsRunningOrForSillyRanges() {
        given(replay.status()).willReturn(status("LIVE"));
        assertThatThrownBy(() -> service.prepare(3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.prepare(0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(twins);
    }
}
