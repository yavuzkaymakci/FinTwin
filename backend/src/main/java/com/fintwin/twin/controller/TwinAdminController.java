package com.fintwin.twin.controller;

import com.fintwin.twin.dto.RebuildResult;
import com.fintwin.twin.service.TwinAdminService;
import com.fintwin.twin.service.TwinRebuildService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Twin maintenance. Admin-only once role-based security lands (currently permit-all). */
@RestController
@RequestMapping("/api/admin/twins")
public class TwinAdminController {

    private final TwinRebuildService rebuildService;
    private final TwinAdminService adminService;

    public TwinAdminController(TwinRebuildService rebuildService, TwinAdminService adminService) {
        this.rebuildService = rebuildService;
        this.adminService = adminService;
    }

    /** Rebuilds all projections from the event store; {@code mode=VERIFY} only compares. */
    @PostMapping("/rebuild")
    public RebuildResult rebuild(@RequestParam(defaultValue = "REPLACE") TwinRebuildService.Mode mode) {
        return rebuildService.run(mode);
    }

    /** Deletes event store and projections (after regenerating the core banking data). */
    @PostMapping("/reset")
    public Map<String, Long> reset() {
        return adminService.reset();
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return adminService.stats();
    }
}
