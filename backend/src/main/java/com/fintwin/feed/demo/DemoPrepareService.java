package com.fintwin.feed.demo;

import com.fintwin.feed.dto.ReplayStatus;
import com.fintwin.feed.publish.ReplayService;
import com.fintwin.twin.projection.TwinProjector;
import com.fintwin.twin.service.TwinAdminService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * Prepares a live demo on a fully replayed data set. The event store is append-only, so instead of rewinding it the
 * twin is rebuilt from scratch: the twin is reset (event store and projection), the feed cursor goes back to the
 * start and everything except the last {@code days} days is bulk-replayed in the background. A following live replay
 * then publishes those days in simulated time and the twins really change.
 */
@Service
public class DemoPrepareService {

    public record DemoPreparation(LocalDate replayedUntil, LocalDate liveFrom, Map<String, Long> twinReset,
                                  ReplayStatus replay) {
    }

    private final ReplayService replay;
    private final TwinAdminService twins;

    public DemoPrepareService(ReplayService replay, TwinAdminService twins) {
        this.replay = replay;
        this.twins = twins;
    }

    public DemoPreparation prepare(int days) {
        if (days < 1 || days > 31) {
            throw new IllegalArgumentException("days must be between 1 and 31");
        }
        ReplayStatus status = replay.status();
        if (!"IDLE".equals(status.mode())) {
            throw new IllegalStateException("Stop the replay first (mode " + status.mode() + ")");
        }
        if (status.feedEnd() == null) {
            throw new IllegalStateException("The outbox is not prepared (POST /api/admin/replay/prepare)");
        }
        LocalDate lastDay = status.feedEnd().atZone(TwinProjector.BANK_ZONE).toLocalDate();
        LocalDate until = lastDay.minusDays(days);
        Map<String, Long> reset = twins.reset();
        replay.reset();
        return new DemoPreparation(until, until.plusDays(1), reset, replay.startBulk(until));
    }
}
