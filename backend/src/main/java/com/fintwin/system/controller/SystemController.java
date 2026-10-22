package com.fintwin.system.controller;

import com.fintwin.system.dto.SystemStatus;
import com.fintwin.system.service.SystemStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** System status for the UI: ML service and models, circuit breaker, AI provider, replay mode. */
@RestController
public class SystemController {

    private final SystemStatusService status;

    public SystemController(SystemStatusService status) {
        this.status = status;
    }

    @GetMapping("/api/system/status")
    public SystemStatus status() {
        return status.status();
    }
}
