package com.fintwin.feed.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Twin balances compared with the core banking balances.
 *
 * @param replayComplete    the whole outbox was published and the twin stored that many events
 * @param mismatchedAccounts accounts whose twin balance differs (or that the twin does not know)
 * @param sample            per-customer comparison for a deterministic sample of customers
 */
public record ConsistencyReport(boolean replayComplete, long outboxEvents, long twinEvents, long accounts,
                                long matchedAccounts, long mismatchedAccounts, List<AccountDiff> firstMismatches,
                                List<CustomerComparison> sample) {

    public record AccountDiff(long customerId, long accountId, String accountType, BigDecimal coreBalance,
                              BigDecimal twinBalance) {
    }

    public record CustomerComparison(long customerId, String segment, int accounts, BigDecimal coreTotal,
                                     BigDecimal twinTotal, boolean match) {
    }
}
