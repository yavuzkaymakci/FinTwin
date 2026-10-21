package com.fintwin.twin.projection;

import com.fintwin.events.v1.TransactionRecordedV1;
import com.fintwin.twin.projection.TransactionClassifier.Flow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionClassifierTests {

    private static TransactionRecordedV1 tx(String direction, String channel, String text, String category,
                                            Long counterpart, String currency) {
        return new TransactionRecordedV1(UUID.randomUUID(), 1, Instant.now(), 1, 1, 1, 1, BigDecimal.TEN, currency,
                direction, channel, 1, text, category == null ? null : 1L, null, category, counterpart);
    }

    private static TransactionClassifier.Classification classify(TransactionRecordedV1 t, String accountType) {
        return TransactionClassifier.classify(t, accountType, null);
    }

    @Test
    void merchantCategoryDrivesSpending() {
        var c = classify(tx("DEBIT", "POS", "BEREKET MRKT 6482 YENIMAHALLE ANK", "GROCERY", null, "TRY"), "CHECKING");
        assertThat(c.flow()).isEqualTo(Flow.SPENDING);
        assertThat(c.category()).isEqualTo("GROCERY");
    }

    @Test
    void recognisesCashRentTransfersAndInterest() {
        assertThat(classify(tx("DEBIT", "ATM", "ATM NAKIT CEKIM 1 KADIKOY IST", null, null, "TRY"), "CHECKING")
                .category()).isEqualTo("CASH_WITHDRAWAL");
        assertThat(classify(tx("DEBIT", "EFT_FAST", "FAST MERVE SEN EV KIRASI OCAK", null, null, "TRY"), "CHECKING")
                .category()).isEqualTo("RENT");
        assertThat(classify(tx("DEBIT", "EFT_FAST", "FAST ALI KAYA HEDIYE", null, null, "TRY"), "CHECKING").flow())
                .isEqualTo(Flow.OUTFLOW);
        assertThat(classify(tx("DEBIT", "SYSTEM", "AKDI FAIZ", null, null, "TRY"), "CREDIT_CARD").flow())
                .isEqualTo(Flow.OUTFLOW);
        assertThat(classify(tx("CREDIT", "SYSTEM", "MEVDUAT FAIZI NET", null, null, "TRY"), "TIME_DEPOSIT").flow())
                .isEqualTo(Flow.NEUTRAL);
    }

    @Test
    void incomeCreditsAndSalaryLikeTexts() {
        var salary = classify(tx("CREDIT", "EFT_FAST", "SGK EMEKLI AYLIGI 03/2025", null, null, "TRY"), "CHECKING");
        assertThat(salary.flow()).isEqualTo(Flow.INCOME);
        assertThat(salary.salaryLike()).isTrue();
        var business = classify(tx("CREDIT", "EFT_FAST", "FAST GELEN ALI KAYA FATURA ODEMESI", null, null, "TRY"),
                "CHECKING");
        assertThat(business.flow()).isEqualTo(Flow.INCOME);
        assertThat(business.salaryLike()).isFalse();
    }

    @Test
    void internalForeignCurrencyAndLoanMovementsAreExcluded() {
        assertThat(classify(tx("DEBIT", "EFT_FAST", "VADELI HESABA VIRMAN", null, 5L, "TRY"), "CHECKING").flow())
                .isEqualTo(Flow.INTERNAL);
        assertThat(classify(tx("CREDIT", "EFT_FAST", "DOVIZ ALIS USD", null, null, "USD"), "CHECKING").flow())
                .isEqualTo(Flow.NEUTRAL);
        assertThat(TransactionClassifier.classify(tx("CREDIT", "EFT_FAST", "KREDI KULLANDIRIM", null, null, "TRY"),
                "CHECKING", "LOAN_PROCEEDS").flow()).isEqualTo(Flow.NEUTRAL);
        assertThat(TransactionClassifier.classify(tx("DEBIT", "AUTO_PAYMENT", "KREDI TAKSIT", null, null, "TRY"),
                "CHECKING", "LOAN_INSTALLMENT").flow()).isEqualTo(Flow.OUTFLOW);
    }
}
