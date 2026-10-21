package com.fintwin.generator.catalog;

import java.util.List;

/**
 * Cities customers live in, with their share of the portfolio, statement abbreviation and districts.
 *
 * @param incomeFactor relative wage level
 * @param rentFactor   relative rent level
 */
public record City(String name, String abbreviation, double weight, double incomeFactor, double rentFactor,
                   List<String> districts) {

    public static final List<City> ALL = List.of(
            new City("ISTANBUL", "IST", 0.30, 1.12, 1.35, List.of("KADIKOY", "BESIKTAS", "SISLI", "USKUDAR",
                    "UMRANIYE", "BAKIRKOY", "ATASEHIR", "ESENYURT", "BAGCILAR", "PENDIK", "MALTEPE", "BEYLIKDUZU")),
            new City("ANKARA", "ANK", 0.10, 1.05, 1.0, List.of("CANKAYA", "KECIOREN", "YENIMAHALLE", "ETIMESGUT",
                    "MAMAK", "SINCAN")),
            new City("IZMIR", "IZM", 0.08, 1.05, 1.1, List.of("KONAK", "KARSIYAKA", "BORNOVA", "BUCA", "BAYRAKLI",
                    "CIGLI")),
            new City("BURSA", "BRS", 0.05, 1.0, 0.9, List.of("NILUFER", "OSMANGAZI", "YILDIRIM")),
            new City("ANTALYA", "ANT", 0.05, 0.98, 1.05, List.of("MURATPASA", "KONYAALTI", "KEPEZ", "ALANYA")),
            new City("KOCAELI", "KOC", 0.04, 1.03, 0.9, List.of("IZMIT", "GEBZE", "DARICA")),
            new City("KONYA", "KNY", 0.04, 0.92, 0.75, List.of("SELCUKLU", "MERAM", "KARATAY")),
            new City("ADANA", "ADN", 0.04, 0.92, 0.75, List.of("SEYHAN", "CUKUROVA", "YUREGIR")),
            new City("GAZIANTEP", "GAZ", 0.04, 0.9, 0.7, List.of("SAHINBEY", "SEHITKAMIL")),
            new City("MERSIN", "MRS", 0.03, 0.92, 0.8, List.of("YENISEHIR", "MEZITLI", "TOROSLAR")),
            new City("KAYSERI", "KYS", 0.03, 0.92, 0.7, List.of("MELIKGAZI", "KOCASINAN", "TALAS")),
            new City("ESKISEHIR", "ESK", 0.03, 0.95, 0.8, List.of("TEPEBASI", "ODUNPAZARI")),
            new City("SAMSUN", "SMS", 0.03, 0.9, 0.7, List.of("ATAKUM", "ILKADIM", "CANIK")),
            new City("DIYARBAKIR", "DYB", 0.03, 0.85, 0.6, List.of("KAYAPINAR", "BAGLAR", "YENISEHIR")),
            new City("TRABZON", "TRB", 0.02, 0.9, 0.75, List.of("ORTAHISAR", "AKCAABAT")),
            new City("DENIZLI", "DNZ", 0.02, 0.92, 0.7, List.of("PAMUKKALE", "MERKEZEFENDI")),
            new City("SAKARYA", "SKY", 0.02, 0.95, 0.75, List.of("ADAPAZARI", "SERDIVAN")));

    /** City used as the registered location of online merchants. */
    public static final City ONLINE_HQ = ALL.getFirst();

    public static double[] weights() {
        return ALL.stream().mapToDouble(City::weight).toArray();
    }
}
