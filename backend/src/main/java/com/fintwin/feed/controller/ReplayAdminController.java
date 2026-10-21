package com.fintwin.feed.controller;

import com.fintwin.feed.consistency.ConsistencyCheckService;
import com.fintwin.feed.dto.ConsistencyReport;
import com.fintwin.feed.dto.PrepareResult;
import com.fintwin.feed.dto.ReplayStatus;
import com.fintwin.feed.publish.ReplayService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Operates the core banking feed. Admin-only once role-based security lands (currently permit-all).
 */
@RestController
@RequestMapping("/api/admin/replay")
public class ReplayAdminController {

    private final ReplayService replayService;
    private final ConsistencyCheckService consistencyCheckService;

    public ReplayAdminController(ReplayService replayService, ConsistencyCheckService consistencyCheckService) {
        this.replayService = replayService;
        this.consistencyCheckService = consistencyCheckService;
    }

    /** Builds the outbox from the core banking tables. */
    @PostMapping("/prepare")
    public PrepareResult prepare() {
        return replayService.prepare();
    }

    /** Publishes all events up to and including {@code until} (default: everything) at full speed. */
    @PostMapping("/bulk")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReplayStatus bulk(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate until) {
        return replayService.startBulk(until);
    }

    /** Publishes in simulated time; {@code secondsPerDay} real seconds per simulated day. */
    @PostMapping("/live")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReplayStatus live(@RequestParam(required = false) Double secondsPerDay,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate from) {
        return replayService.startLive(secondsPerDay, from);
    }

    @PostMapping("/stop")
    public ReplayStatus stop() {
        return replayService.stop();
    }

    @PostMapping("/speed")
    public ReplayStatus speed(@RequestParam double secondsPerDay) {
        return replayService.changeSpeed(secondsPerDay);
    }

    /** Rewinds the cursor to the beginning of the outbox. */
    @PostMapping("/reset")
    public ReplayStatus reset() {
        return replayService.reset();
    }

    @GetMapping("/status")
    public ReplayStatus status() {
        return replayService.status();
    }

    /** Compares twin balances with the core banking balances (meaningful after a complete replay). */
    @GetMapping("/consistency")
    public ConsistencyReport consistency(@RequestParam(defaultValue = "10") int sample) {
        return consistencyCheckService.check(sample);
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(RuntimeException e) {
        return Map.of("error", e.getMessage());
    }
}
