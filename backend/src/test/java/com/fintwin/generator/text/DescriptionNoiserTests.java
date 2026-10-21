package com.fintwin.generator.text;

import com.fintwin.generator.rng.Rand;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionNoiserTests {

    private static final String STATEMENT_CHARSET = "^[A-Z0-9 *./:()#'-]+$";

    @Test
    void foldsTurkishLettersToUpperCaseAscii() {
        assertThat(DescriptionNoiser.fold("Kadıköy Şişli Çağlayan Gün Öz İzmir"))
                .isEqualTo("KADIKOY SISLI CAGLAYAN GUN OZ IZMIR");
        assertThat(DescriptionNoiser.fold("a &  b")).isEqualTo("A B");
    }

    @Test
    void posDescriptorsLookLikeStatementLines() {
        Rand r = new Rand(1);
        List<String> descriptors = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            descriptors.add(DescriptionNoiser.posDescriptor("Kardeşler Market", "KADIKOY", "IST", r));
        }
        assertThat(descriptors).allSatisfy(d -> {
            assertThat(d).matches(STATEMENT_CHARSET);
            assertThat(d.length()).isLessThanOrEqualTo(DescriptionNoiser.POS_MAX_LENGTH);
            assertThat(d).startsWith("KARDESLER");
        });
        // Abbreviations and formats vary: "MKT", "MRKT", terminal numbers, district/city codes...
        assertThat(new HashSet<>(descriptors).size()).isGreaterThan(50);
        assertThat(descriptors).anyMatch(d -> d.contains(" MKT") || d.contains(" MRKT"));
        assertThat(descriptors).anyMatch(d -> d.matches(".* \\d{4} KADIKOY IST$"));
    }

    @Test
    void onlineDescriptorsUseGatewayOrWebForms() {
        Rand r = new Rand(2);
        List<String> descriptors = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            descriptors.add(DescriptionNoiser.onlineDescriptor("TIKLA AL", r));
        }
        assertThat(descriptors).allSatisfy(d -> assertThat(d).matches(STATEMENT_CHARSET));
        assertThat(descriptors).anyMatch(d -> d.startsWith("IYZICO*") || d.startsWith("PAYTR *"));
        assertThat(descriptors).anyMatch(d -> d.contains(".COM"));
    }

    @Test
    void purchaseAddsInstallmentMarkerAndStaysWithinLimits() {
        Rand r = new Rand(3);
        for (int i = 0; i < 500; i++) {
            String single = DescriptionNoiser.purchase("TEKNO MARKET 1234 CANKAYA ANK", 1, r);
            String split = DescriptionNoiser.purchase("TEKNO MARKET 1234 CANKAYA ANK", 6, r);
            assertThat(single).doesNotContain("TAKSIT").doesNotContain("TKS");
            assertThat(split).containsAnyOf("6 TAKSIT", "TKS:6", "(6 TKS)");
            assertThat(split.length()).isLessThanOrEqualTo(DescriptionNoiser.MAX_LENGTH);
        }
    }

    @Test
    void sameRandomStreamGivesSameText() {
        Rand a = new Rand(9);
        Rand b = new Rand(9);
        for (int i = 0; i < 100; i++) {
            assertThat(DescriptionNoiser.salary("ANADOLU GIDA SANAYI A.S.", YearMonth.of(2025, 3), a))
                    .isEqualTo(DescriptionNoiser.salary("ANADOLU GIDA SANAYI A.S.", YearMonth.of(2025, 3), b));
        }
    }

    @Test
    void transferAndBillTextsAreFoldedAndBounded() {
        Rand r = new Rand(4);
        List<String> texts = List.of(
                DescriptionNoiser.rent("AYŞE YILMAZ", YearMonth.of(2025, 8), r),
                DescriptionNoiser.bill("İSTANBUL ELEKTRİK PERAKENDE SATIŞ", "123456789", r),
                DescriptionNoiser.atmWithdrawal("KADIKOY", "IST", false, r),
                DescriptionNoiser.atmWithdrawal("KADIKOY", "IST", true, r),
                DescriptionNoiser.loanInstallment("1234567890", 3, 24, r),
                DescriptionNoiser.cardPaymentFromAccount("4321", true, r));
        assertThat(texts).allSatisfy(t -> {
            assertThat(t).matches(STATEMENT_CHARSET);
            assertThat(t.length()).isLessThanOrEqualTo(DescriptionNoiser.MAX_LENGTH);
        });
        assertThat(texts.get(3)).contains("AVANS");
    }
}
