package com.fintwin.twin.risk.controller;

import com.fintwin.twin.risk.client.MlServiceUnavailableException;
import com.fintwin.twin.risk.dto.RiskScoreView;
import com.fintwin.twin.risk.dto.ScoringStatus;
import com.fintwin.twin.risk.service.RiskScoringService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RiskController {

    private final RiskScoringService scoring;

    public RiskController(RiskScoringService scoring) {
        this.scoring = scoring;
    }

    /** Latest stored risk score; {@code refresh=true} scores the current twin now (needs the ML service). */
    @GetMapping("/api/twins/{customerId}/risk")
    public ResponseEntity<RiskScoreView> risk(@PathVariable long customerId,
                                              @RequestParam(defaultValue = "false") boolean refresh) {
        return (refresh ? scoring.scoreNow(customerId) : scoring.latest(customerId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Scores every twin in the background (admin-only once security lands). */
    @PostMapping("/api/admin/risk/score-portfolio")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ScoringStatus scorePortfolio() {
        return scoring.startPortfolioScoring();
    }

    @GetMapping("/api/admin/risk/status")
    public ScoringStatus status() {
        return scoring.status();
    }

    @ExceptionHandler(MlServiceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> mlUnavailable(MlServiceUnavailableException e) {
        return Map.of("error", "ML_SERVICE_UNAVAILABLE", "detail", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException e) {
        return Map.of("error", e.getMessage());
    }
}
