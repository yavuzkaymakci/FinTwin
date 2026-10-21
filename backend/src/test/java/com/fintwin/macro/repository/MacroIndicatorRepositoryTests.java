package com.fintwin.macro.repository;

import com.fintwin.RepositoryTest;
import com.fintwin.macro.domain.MacroIndicator;
import com.fintwin.macro.domain.MacroIndicatorType;
import com.fintwin.merchant.repository.MerchantCategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class MacroIndicatorRepositoryTests {

    @Autowired
    private MacroIndicatorRepository repository;

    @Autowired
    private MerchantCategoryRepository categoryRepository;

    @Test
    void seedCoversEveryIndicatorForEveryMonth() {
        for (MacroIndicatorType type : MacroIndicatorType.values()) {
            List<MacroIndicator> series = repository.findByIndicatorOrderByPeriodAsc(type);
            assertThat(series).hasSize(33);
            assertThat(series.getFirst().getPeriod()).isEqualTo(LocalDate.of(2024, 1, 1));
            assertThat(series.getLast().getPeriod()).isEqualTo(LocalDate.of(2026, 9, 1));
        }
        assertThat(repository.findByIndicatorAndPeriod(MacroIndicatorType.POLICY_RATE, LocalDate.of(2024, 3, 1)))
                .get().extracting(MacroIndicator::getValue).satisfies(v -> assertThat(v).isEqualByComparingTo("50"));
    }

    @Test
    void seedsMerchantCategories() {
        assertThat(categoryRepository.count()).isEqualTo(21);
        assertThat(categoryRepository.findByCode("GROCERY")).isPresent();
    }
}
