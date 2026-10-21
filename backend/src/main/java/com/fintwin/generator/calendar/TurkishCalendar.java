package com.fintwin.generator.calendar;

import com.fintwin.generator.catalog.Category;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * Turkish retail calendar effects: weekends, public holidays, the two religious holidays (bayram), Black Friday,
 * New Year and the summer season.
 */
public final class TurkishCalendar {

    /** Inclusive date range of a religious holiday. */
    public record Holiday(String name, LocalDate start, LocalDate end) {

        boolean contains(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(end);
        }
    }

    /** Ramazan and Kurban Bayramı dates (lunar calendar, published by Diyanet). */
    public static final List<Holiday> RELIGIOUS_HOLIDAYS = List.of(
            new Holiday("RAMAZAN_BAYRAMI", LocalDate.of(2023, 4, 21), LocalDate.of(2023, 4, 23)),
            new Holiday("KURBAN_BAYRAMI", LocalDate.of(2023, 6, 28), LocalDate.of(2023, 7, 1)),
            new Holiday("RAMAZAN_BAYRAMI", LocalDate.of(2024, 4, 10), LocalDate.of(2024, 4, 12)),
            new Holiday("KURBAN_BAYRAMI", LocalDate.of(2024, 6, 16), LocalDate.of(2024, 6, 19)),
            new Holiday("RAMAZAN_BAYRAMI", LocalDate.of(2025, 3, 30), LocalDate.of(2025, 4, 1)),
            new Holiday("KURBAN_BAYRAMI", LocalDate.of(2025, 6, 6), LocalDate.of(2025, 6, 9)),
            new Holiday("RAMAZAN_BAYRAMI", LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 22)),
            new Holiday("KURBAN_BAYRAMI", LocalDate.of(2026, 5, 27), LocalDate.of(2026, 5, 30)),
            new Holiday("RAMAZAN_BAYRAMI", LocalDate.of(2027, 3, 9), LocalDate.of(2027, 3, 11)),
            new Holiday("KURBAN_BAYRAMI", LocalDate.of(2027, 5, 16), LocalDate.of(2027, 5, 19)));

    private static final Set<MonthDay> PUBLIC_HOLIDAYS = Set.of(
            MonthDay.of(1, 1), MonthDay.of(4, 23), MonthDay.of(5, 1), MonthDay.of(5, 19),
            MonthDay.of(7, 15), MonthDay.of(8, 30), MonthDay.of(10, 29));

    private static final int PRE_HOLIDAY_SHOPPING_DAYS = 7;

    private TurkishCalendar() {
    }

    public static boolean isWeekend(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    public static boolean isPublicHoliday(LocalDate date) {
        return PUBLIC_HOLIDAYS.contains(MonthDay.from(date));
    }

    public static boolean isReligiousHoliday(LocalDate date) {
        return RELIGIOUS_HOLIDAYS.stream().anyMatch(h -> h.contains(date));
    }

    /** True during the week before a bayram, when households shop for clothes, sweets and gifts. */
    public static boolean isPreHolidayShopping(LocalDate date) {
        return RELIGIOUS_HOLIDAYS.stream().anyMatch(h ->
                date.isBefore(h.start()) && !date.isBefore(h.start().minusDays(PRE_HOLIDAY_SHOPPING_DAYS)));
    }

    public static boolean isBusinessDay(LocalDate date) {
        return !isWeekend(date) && !isPublicHoliday(date) && !isReligiousHoliday(date);
    }

    /** Payments scheduled on a non-business day are made on the previous business day. */
    public static LocalDate previousBusinessDay(LocalDate date) {
        LocalDate d = date;
        while (!isBusinessDay(d)) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** Relative transaction intensity of a category on a given day (1.0 = ordinary weekday). */
    public static double dayMultiplier(Category category, LocalDate date) {
        double m = 1.0;
        boolean dayOff = isWeekend(date) || isPublicHoliday(date);
        if (dayOff) {
            m *= switch (category) {
                case RESTAURANT -> 1.6;
                case ENTERTAINMENT -> 1.8;
                case GROCERY -> 1.3;
                case CLOTHING -> 1.4;
                case FUEL -> 1.15;
                case ECOMMERCE -> 1.1;
                case TRANSPORT -> 0.6;
                case EDUCATION, HEALTH -> 0.5;
                default -> 1.0;
            };
        }
        if (isPreHolidayShopping(date)) {
            m *= switch (category) {
                case CLOTHING -> 2.2;
                case GROCERY -> 1.4;
                case ELECTRONICS -> 1.2;
                case FUEL -> 1.3;
                case TRAVEL -> 1.8;
                default -> 1.0;
            };
        }
        if (isReligiousHoliday(date)) {
            m *= switch (category) {
                case TRAVEL -> 2.5;
                case FUEL -> 1.8;
                case RESTAURANT -> 1.3;
                case ENTERTAINMENT -> 1.3;
                case GROCERY -> 0.7;
                case CLOTHING, EDUCATION -> 0.5;
                default -> 1.0;
            };
        }
        if (date.getMonth() == Month.NOVEMBER && date.getDayOfMonth() >= 20) {
            // Black Friday / "Efsane Cuma" week.
            m *= switch (category) {
                case ECOMMERCE -> 2.2;
                case ELECTRONICS -> 1.8;
                case CLOTHING -> 1.5;
                default -> 1.0;
            };
        }
        if (date.getMonth() == Month.DECEMBER && date.getDayOfMonth() >= 20) {
            m *= switch (category) {
                case ECOMMERCE -> 1.4;
                case CLOTHING, ELECTRONICS -> 1.3;
                case ENTERTAINMENT -> 1.4;
                case RESTAURANT -> date.getDayOfMonth() == 31 ? 2.2 : 1.2;
                default -> 1.0;
            };
        }
        return m;
    }

    /** Seasonal intensity of a category in a month, on top of the day multipliers. */
    public static double seasonalMultiplier(Category category, YearMonth month) {
        Month m = month.getMonth();
        return switch (category) {
            case TRAVEL -> switch (m) {
                case JULY, AUGUST -> 2.2;
                case JUNE, SEPTEMBER -> 1.4;
                case JANUARY, FEBRUARY, NOVEMBER -> 0.6;
                default -> 1.0;
            };
            case EDUCATION -> switch (m) {
                case SEPTEMBER -> 3.0;
                case OCTOBER, FEBRUARY -> 1.4;
                case JULY, AUGUST -> 0.5;
                default -> 1.0;
            };
            case RESTAURANT, ENTERTAINMENT -> switch (m) {
                case JUNE, JULY, AUGUST -> 1.15;
                default -> 1.0;
            };
            case FUEL -> switch (m) {
                case JULY, AUGUST -> 1.2;
                default -> 1.0;
            };
            case CLOTHING -> switch (m) {
                case SEPTEMBER, OCTOBER -> 1.2;
                case JANUARY, JULY -> 1.15; // seasonal sales
                default -> 1.0;
            };
            default -> 1.0;
        };
    }

    /** Monthly natural gas consumption relative to the yearly average (heating season). */
    public static double gasSeasonality(YearMonth month) {
        return switch (month.getMonth()) {
            case JANUARY -> 2.6;
            case FEBRUARY -> 2.3;
            case MARCH -> 1.7;
            case APRIL -> 1.0;
            case MAY -> 0.55;
            case JUNE, JULY, AUGUST -> 0.3;
            case SEPTEMBER -> 0.4;
            case OCTOBER -> 0.8;
            case NOVEMBER -> 1.5;
            case DECEMBER -> 2.2;
        };
    }

    /** Monthly electricity consumption relative to the yearly average (air conditioning in summer). */
    public static double electricitySeasonality(YearMonth month) {
        return switch (month.getMonth()) {
            case JULY, AUGUST -> 1.25;
            case DECEMBER, JANUARY, FEBRUARY -> 1.1;
            case APRIL, MAY, OCTOBER -> 0.85;
            default -> 1.0;
        };
    }
}
