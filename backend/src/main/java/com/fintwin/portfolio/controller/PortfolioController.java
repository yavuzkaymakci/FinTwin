package com.fintwin.portfolio.controller;

import com.fintwin.portfolio.dto.PortfolioSummary;
import com.fintwin.portfolio.dto.SegmentSummary;
import com.fintwin.portfolio.service.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Portfolio dashboard: KPIs, grade distribution, segments and the riskiest customers. */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolio;

    public PortfolioController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping("/summary")
    public PortfolioSummary summary(@RequestParam(defaultValue = "10") int top) {
        return portfolio.summary(Math.max(1, Math.min(top, 50)));
    }

    @GetMapping("/segments")
    public List<SegmentSummary> segments() {
        return portfolio.segments();
    }
}
