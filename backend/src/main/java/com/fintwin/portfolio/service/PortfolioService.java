package com.fintwin.portfolio.service;

import com.fintwin.portfolio.dto.PortfolioSummary;
import com.fintwin.portfolio.dto.SegmentSummary;
import com.fintwin.portfolio.repository.PortfolioRepository;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.List;

/** Portfolio dashboard figures. */
@Service
public class PortfolioService {

    /** LGD of the dashboard's expected loss (unsecured retail; the simulation distinguishes products). */
    public static final double LGD = 0.70;

    private final PortfolioRepository repository;

    public PortfolioService(PortfolioRepository repository) {
        this.repository = repository;
    }

    public PortfolioSummary summary(int topCustomers) {
        PortfolioRepository.Totals t = repository.totals(LGD);
        return new PortfolioSummary(t.scoredAt(), t.activeCustomers(), t.scoredCustomers(), t.meanPd(), t.meanChurn(),
                t.totalDebt().setScale(2, RoundingMode.HALF_UP), t.expectedLoss().setScale(2, RoundingMode.HALF_UP),
                LGD, t.highRisk(), repository.grades(), segments(), repository.topRisk(topCustomers));
    }

    public List<SegmentSummary> segments() {
        return repository.segments(LGD).stream().map(s -> new SegmentSummary(s.segment(), s.customers(), s.meanPd(),
                s.meanChurn(), s.expectedLoss().setScale(2, RoundingMode.HALF_UP),
                s.totalDebt().setScale(2, RoundingMode.HALF_UP), s.meanUtilization(), s.meanLiquidityBufferMonths(),
                s.highRiskCustomers())).toList();
    }
}
