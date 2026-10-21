package com.fintwin.events.v1;

import com.fintwin.events.CoreBankingEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A posted account movement.
 *
 * @param amount always positive; see direction
 * @param direction DEBIT or CREDIT
 * @param channel POS, ECOMMERCE, ATM, EFT_FAST, AUTO_PAYMENT or SYSTEM
 * @param rawDescription statement text as printed
 * @param merchantId null for non-merchant movements
 * @param merchantCategory acquirer category of the merchant (MCC group), null without merchant
 * @param counterpartAccountId set for transfers between the customer's own accounts
 */
public record TransactionRecordedV1(
        UUID eventId,
        int schemaVersion,
        Instant occurredAt,
        long customerId,
        long customerSequence,
        long transactionId,
        long accountId,
        BigDecimal amount,
        String currency,
        String direction,
        String channel,
        int installmentCount,
        String rawDescription,
        Long merchantId,
        String merchantName,
        String merchantCategory,
        Long counterpartAccountId) implements CoreBankingEvent {

    public static final String TYPE = "TransactionRecorded";
    public static final int SCHEMA_VERSION = 1;

    @Override
    public String eventType() {
        return TYPE;
    }
}
