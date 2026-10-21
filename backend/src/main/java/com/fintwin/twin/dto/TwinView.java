package com.fintwin.twin.dto;

import com.fintwin.twin.projection.TwinFeatures;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A customer's twin at a point in time.
 *
 * @param asOf         evaluation instant of the features
 * @param source       CURRENT (stored projection) or REPLAYED (snapshot + replay for an as-of query)
 * @param lastSequence last applied event of the customer's stream
 */
public record TwinView(long customerId, Instant asOf, String source, long lastSequence, Instant lastEventAt,
                       String segment, String city, Integer age, LocalDate customerSince,
                       List<AccountView> accounts, List<LoanView> loans, TwinFeatures features) {

    public record AccountView(long accountId, String type, String currency, java.math.BigDecimal balance,
                              java.math.BigDecimal creditLimit, boolean closed) {
    }

    public record LoanView(long loanId, String type, java.math.BigDecimal installment,
                           java.math.BigDecimal outstanding, int remainingInstallments, boolean active) {
    }
}
