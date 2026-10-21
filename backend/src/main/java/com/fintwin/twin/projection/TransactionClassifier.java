package com.fintwin.twin.projection;

import com.fintwin.events.v1.TransactionRecordedV1;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rule-based interpretation of a transaction from what the event stream exposes (account type, direction, channel,
 * merchant category, counterpart link, statement text). A placeholder for the NLP categorisation model; it never
 * sees the generator's true labels.
 */
public final class TransactionClassifier {

    /** How a transaction affects the twin's flow features. */
    public enum Flow {
        /** Transfer between the customer's own accounts: no income, no expense. */
        INTERNAL,
        /** Consumer spending; counted in spending categories and outflows. */
        SPENDING,
        /** Other external outflow (rent is spending; transfers out, installments, interest, closures are not). */
        OUTFLOW,
        /** External income credit (salary, pension, scholarship, business receipts, incoming transfers). */
        INCOME,
        /** Credits that are neither income nor internal (loan proceeds, interest earned, USD movements). */
        NEUTRAL
    }

    public record Classification(Flow flow, String category, boolean salaryLike) {
    }

    private static final Pattern SALARY = Pattern.compile(
            "\\b(MAAS|EMEKLI|EMK|SGK|KYK|BURS|ISKUR|ISSIZLIK|PRIM|IKRAMIYE|TAZMINAT|AYLIG|AYLIK)");
    private static final Pattern RENT = Pattern.compile("\\bKIRA");

    private TransactionClassifier() {
    }

    /**
     * @param accountType       type of the account the transaction was posted to (null if unknown)
     * @param matchedLoanFlow   kind of the pending loan movement this transaction matched, or null
     */
    public static Classification classify(TransactionRecordedV1 t, String accountType, String matchedLoanFlow) {
        boolean credit = "CREDIT".equals(t.direction());
        String text = t.rawDescription() == null ? "" : t.rawDescription().toUpperCase(Locale.ROOT);
        if (t.counterpartAccountId() != null) {
            return new Classification(Flow.INTERNAL, null, false);
        }
        if (!"TRY".equals(t.currency())) {
            return new Classification(Flow.NEUTRAL, null, false);
        }
        if (matchedLoanFlow != null) {
            return switch (matchedLoanFlow) {
                case "LOAN_INSTALLMENT" -> new Classification(Flow.OUTFLOW, "LOAN", false);
                default -> new Classification(Flow.NEUTRAL, "LOAN", false);
            };
        }
        if ("SYSTEM".equals(t.channel())) {
            return credit ? new Classification(Flow.NEUTRAL, "INTEREST", false)
                    : new Classification(Flow.OUTFLOW, "INTEREST", false);
        }
        if (credit) {
            if ("CREDIT_CARD".equals(accountType)) {
                return new Classification(Flow.NEUTRAL, null, false);
            }
            return new Classification(Flow.INCOME, null, SALARY.matcher(text).find());
        }
        if (t.merchantCategory() != null) {
            return new Classification(Flow.SPENDING, t.merchantCategory(), false);
        }
        if ("ATM".equals(t.channel())) {
            return new Classification(Flow.SPENDING, "CASH_WITHDRAWAL", false);
        }
        if (RENT.matcher(text).find()) {
            return new Classification(Flow.SPENDING, "RENT", false);
        }
        return new Classification(Flow.OUTFLOW, "TRANSFER", false);
    }
}
