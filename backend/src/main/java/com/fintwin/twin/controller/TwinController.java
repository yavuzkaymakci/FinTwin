package com.fintwin.twin.controller;

import com.fintwin.twin.dto.FeatureHistory;
import com.fintwin.twin.dto.TwinEventView;
import com.fintwin.twin.dto.TwinView;
import com.fintwin.twin.service.TwinQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/twins")
public class TwinController {

    private final TwinQueryService queryService;

    public TwinController(TwinQueryService queryService) {
        this.queryService = queryService;
    }

    /** Current twin, or the twin at the end of {@code asOf} (bank time). */
    @GetMapping("/{customerId}")
    public TwinView twin(@PathVariable long customerId,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                         LocalDate asOf) {
        return asOf == null ? queryService.current(customerId) : queryService.asOf(customerId, asOf);
    }

    /** Events the twin processed between {@code from} and {@code to} (inclusive dates, bank time). */
    @GetMapping("/{customerId}/events")
    public List<TwinEventView> events(@PathVariable long customerId,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queryService.events(customerId, from, to);
    }

    /** Monthly series of a scalar feature, e.g. {@code feature=utilizationRate}. */
    @GetMapping("/{customerId}/history")
    public FeatureHistory history(@PathVariable long customerId, @RequestParam String feature) {
        return queryService.history(customerId, feature);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
