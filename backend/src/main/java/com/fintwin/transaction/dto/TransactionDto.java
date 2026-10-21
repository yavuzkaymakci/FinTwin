package com.fintwin.transaction.dto;

import com.fintwin.account.domain.Currency;
import com.fintwin.transaction.domain.Channel;
import com.fintwin.transaction.domain.Direction;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionDto(
        Long id,
        Long accountId,
        BigDecimal amount,
        Currency currency,
        Direction direction,
        Channel channel,
        int installmentCount,
        String rawDescription,
        String merchantName,
        Instant occurredAt) {
}
