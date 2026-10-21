package com.fintwin.generator.calendar;

import com.fintwin.generator.catalog.Category;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class TurkishCalendarTests {

    @Test
    void knowsReligiousHolidaysAndThePreHolidayWeek() {
        assertThat(TurkishCalendar.isReligiousHoliday(LocalDate.of(2025, 3, 31))).isTrue();
        assertThat(TurkishCalendar.isReligiousHoliday(LocalDate.of(2026, 5, 28))).isTrue();
        assertThat(TurkishCalendar.isReligiousHoliday(LocalDate.of(2025, 4, 2))).isFalse();
        assertThat(TurkishCalendar.isPreHolidayShopping(LocalDate.of(2025, 3, 25))).isTrue();
        assertThat(TurkishCalendar.isPreHolidayShopping(LocalDate.of(2025, 3, 30))).isFalse();
    }

    @Test
    void movesPaymentsToThePreviousBusinessDay() {
        // 2025-03-30 (Sunday) is also the first day of Ramazan Bayramı -> Friday 28th.
        assertThat(TurkishCalendar.previousBusinessDay(LocalDate.of(2025, 3, 30))).isEqualTo(LocalDate.of(2025, 3, 28));
        assertThat(TurkishCalendar.previousBusinessDay(LocalDate.of(2025, 10, 29))).isEqualTo(LocalDate.of(2025, 10, 28));
        assertThat(TurkishCalendar.previousBusinessDay(LocalDate.of(2025, 10, 15))).isEqualTo(LocalDate.of(2025, 10, 15));
    }

    @Test
    void appliesWeekendHolidayAndShoppingSeasonEffects() {
        LocalDate wednesday = LocalDate.of(2025, 10, 15);
        LocalDate saturday = LocalDate.of(2025, 10, 18);
        assertThat(TurkishCalendar.dayMultiplier(Category.RESTAURANT, saturday))
                .isGreaterThan(TurkishCalendar.dayMultiplier(Category.RESTAURANT, wednesday));
        assertThat(TurkishCalendar.dayMultiplier(Category.CLOTHING, LocalDate.of(2025, 3, 26)))
                .isGreaterThan(2.0);
        assertThat(TurkishCalendar.dayMultiplier(Category.TRAVEL, LocalDate.of(2025, 6, 7))).isGreaterThan(2.0);
        assertThat(TurkishCalendar.dayMultiplier(Category.ECOMMERCE, LocalDate.of(2025, 11, 26)))
                .isGreaterThan(2.0);
        assertThat(TurkishCalendar.seasonalMultiplier(Category.TRAVEL, YearMonth.of(2025, 8)))
                .isGreaterThan(TurkishCalendar.seasonalMultiplier(Category.TRAVEL, YearMonth.of(2025, 2)));
        assertThat(TurkishCalendar.seasonalMultiplier(Category.EDUCATION, YearMonth.of(2025, 9))).isEqualTo(3.0);
    }

    @Test
    void heatingDrivesGasBills() {
        assertThat(TurkishCalendar.gasSeasonality(YearMonth.of(2025, 1)))
                .isGreaterThan(5 * TurkishCalendar.gasSeasonality(YearMonth.of(2025, 7)));
    }
}
