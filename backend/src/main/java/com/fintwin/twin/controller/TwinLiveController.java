package com.fintwin.twin.controller;

import com.fintwin.twin.dto.TwinLiveView;
import com.fintwin.twin.service.TwinLiveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Live feed view: consumer counters and the most recently stored events. */
@RestController
public class TwinLiveController {

    private final TwinLiveService live;

    public TwinLiveController(TwinLiveService live) {
        this.live = live;
    }

    @GetMapping("/api/live/twin")
    public TwinLiveView twin(@RequestParam(defaultValue = "20") int limit) {
        return live.live(Math.max(1, Math.min(limit, 100)));
    }
}
