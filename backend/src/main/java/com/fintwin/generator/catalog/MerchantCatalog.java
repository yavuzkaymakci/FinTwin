package com.fintwin.generator.catalog;

import com.fintwin.generator.rng.Rand;
import com.fintwin.generator.rng.SeededRandoms;
import com.fintwin.generator.text.DescriptionNoiser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic set of fictional merchants: local shops and national chain outlets per city, online merchants,
 * and utility/telecom billers. Ids are assigned in creation order starting at 1.
 */
public final class MerchantCatalog {

    /**
     * @param descriptors stable statement descriptors (one per POS terminal / online integration)
     */
    public record MerchantRef(long id, String name, Category category, City city, String district, boolean online,
                              List<String> descriptors) {
    }

    private record LocalSpec(int maxCount, int minCount, List<String> chains, List<String> templates) {
    }

    private static final Map<Category, LocalSpec> LOCAL_SPECS = new EnumMap<>(Category.class);
    private static final Map<Category, List<String>> ONLINE_NAMES = new EnumMap<>(Category.class);

    static {
        LOCAL_SPECS.put(Category.GROCERY, new LocalSpec(40, 4,
                List.of("YILDIZ MARKET", "EKOMAR SUPERMARKET", "BEREKET MARKET", "TAZE GROSS MARKET", "UCUZLUK MARKET"),
                List.of("{S} MARKET", "{S} BAKKAL", "{S} KASAP", "{S} SARKUTERI", "{S} MANAV", "{S} FIRIN")));
        LOCAL_SPECS.put(Category.FUEL, new LocalSpec(10, 2,
                List.of("ANADOLU PETROL", "MAVI AKARYAKIT", "YOLCU PETROL", "KARTAL AKARYAKIT"),
                List.of("{S} PETROL", "{S} AKARYAKIT")));
        LOCAL_SPECS.put(Category.RESTAURANT, new LocalSpec(40, 4,
                List.of("BURGER HANE", "PIZZA ATOLYE", "KAHVE KOSESI"),
                List.of("{S} KEBAP", "{S} PIDE SALONU", "{S} LOKANTASI", "SIMITCI {S}", "{S} CAY BAHCESI",
                        "{S} RESTORAN", "{S} BALIK EVI", "{S} KAFE")));
        LOCAL_SPECS.put(Category.CLOTHING, new LocalSpec(15, 2,
                List.of("MODA EVI MAGAZACILIK", "ADIM AYAKKABI", "TEKSTIL DUNYASI", "STIL BUTIK"),
                List.of("{S} GIYIM", "{S} AYAKKABI", "{S} BUTIK")));
        LOCAL_SPECS.put(Category.ELECTRONICS, new LocalSpec(8, 1,
                List.of("TEKNO MARKET", "ELEKTRO DUNYA", "DIJITAL EV"),
                List.of("{S} ELEKTRONIK", "{S} BEYAZ ESYA")));
        LOCAL_SPECS.put(Category.HEALTH, new LocalSpec(20, 3,
                List.of(),
                List.of("{S} ECZANESI", "{S} ECZANESI", "{S} ECZANESI", "OZEL {S} HASTANESI", "{S} DIS POLIKLINIGI",
                        "{S} GOZ MERKEZI")));
        LOCAL_SPECS.put(Category.ENTERTAINMENT, new LocalSpec(5, 1,
                List.of("SINEMA PARK"),
                List.of("{S} BOWLING", "{S} OYUN SALONU", "{S} SAHNE KONSER")));
        LOCAL_SPECS.put(Category.EDUCATION, new LocalSpec(8, 2,
                List.of("BILGI KITABEVI"),
                List.of("{S} KIRTASIYE", "{S} DERSHANESI", "{S} DIL AKADEMI", "OZEL {S} KOLEJI")));
        LOCAL_SPECS.put(Category.TRANSPORT, new LocalSpec(4, 2,
                List.of(),
                List.of("{C}KART DOLUM", "{S} TAKSI", "OTOPARK {S}")));
        LOCAL_SPECS.put(Category.TRAVEL, new LocalSpec(4, 1,
                List.of(),
                List.of("{S} TURIZM", "{S} OTEL", "{C} OTOGAR BILET")));

        ONLINE_NAMES.put(Category.ECOMMERCE, List.of("PAZARYERI.COM", "TIKLA AL", "SEPETIM", "KAPIDA AL",
                "HER SEY BURADA", "FIRSAT DUNYASI"));
        ONLINE_NAMES.put(Category.CLOTHING, List.of("MODA ONLINE", "BUTIK SEPET"));
        ONLINE_NAMES.put(Category.ELECTRONICS, List.of("TEKNOSEPET", "DIJITAL EV ONLINE"));
        ONLINE_NAMES.put(Category.RESTAURANT, List.of("LEZZETLI KAPIDA", "HIZLI YEMEK", "ACIKTIM"));
        ONLINE_NAMES.put(Category.TRAVEL, List.of("GOKYUZU HAVAYOLLARI", "UCUZ UCUS", "TATIL DUNYASI TURIZM",
                "OTOBUSUM BILET"));
        ONLINE_NAMES.put(Category.ENTERTAINMENT, List.of("DIZI PLATFORMU", "MUZIK ABONELIK", "OYUN MAGAZASI",
                "BILET DUKKANI"));
        ONLINE_NAMES.put(Category.EDUCATION, List.of("ONLINE KURS AKADEMI", "E-KITAP DUNYASI"));
        ONLINE_NAMES.put(Category.GROCERY, List.of("HIZLI MARKET", "KAPIDA MANAV"));
        ONLINE_NAMES.put(Category.TRANSPORT, List.of("SEHIR KART MOBIL", "YOLCU TAKSI APP"));
        ONLINE_NAMES.put(Category.TELECOM, List.of("HATTIM GSM", "BAGLANTI TELEKOM", "NET FIBER INTERNET",
                "EKRAN DIJITAL TV"));
    }

    private final List<MerchantRef> all = new ArrayList<>();
    private final Map<String, Map<Category, List<MerchantRef>>> localByCity = new HashMap<>();
    private final Map<Category, List<MerchantRef>> onlineByCategory = new EnumMap<>(Category.class);
    private final Map<String, List<MerchantRef>> utilitiesByCity = new HashMap<>();

    private MerchantCatalog() {
    }

    public static MerchantCatalog build(long seed) {
        MerchantCatalog catalog = new MerchantCatalog();
        Rand r = SeededRandoms.forStream(seed, SeededRandoms.Stream.MERCHANT_CATALOG, 0);
        double maxWeight = City.ALL.stream().mapToDouble(City::weight).max().orElse(1);
        for (City city : City.ALL) {
            Map<Category, List<MerchantRef>> byCategory = new EnumMap<>(Category.class);
            for (Map.Entry<Category, LocalSpec> e : LOCAL_SPECS.entrySet()) {
                Category category = e.getKey();
                LocalSpec spec = e.getValue();
                List<MerchantRef> merchants = new ArrayList<>();
                int outletsPerChain = 1 + (int) Math.round(city.weight() * 10);
                for (String chain : spec.chains()) {
                    for (int i = 0; i < outletsPerChain; i++) {
                        merchants.add(catalog.addPos(chain, category, city, r));
                    }
                }
                int count = Math.max(spec.minCount(), (int) Math.round(spec.maxCount() * city.weight() / maxWeight));
                for (int i = 0; i < count; i++) {
                    String name = r.pick(spec.templates())
                            .replace("{S}", r.pick(Names.SURNAMES))
                            .replace("{C}", city.name());
                    merchants.add(catalog.addPos(name, category, city, r));
                }
                byCategory.put(category, List.copyOf(merchants));
            }
            catalog.localByCity.put(city.name(), byCategory);
            catalog.utilitiesByCity.put(city.name(), List.of(
                    catalog.addBiller(city.name() + " ELEKTRIK PERAKENDE SATIS", Category.UTILITIES, city, r),
                    catalog.addBiller(city.name() + " DOGALGAZ DAGITIM", Category.UTILITIES, city, r),
                    catalog.addBiller(city.name() + " SU VE KANALIZASYON IDARESI", Category.UTILITIES, city, r)));
        }
        for (Map.Entry<Category, List<String>> e : ONLINE_NAMES.entrySet()) {
            List<MerchantRef> merchants = new ArrayList<>();
            for (String name : e.getValue()) {
                merchants.add(e.getKey() == Category.TELECOM
                        ? catalog.addBiller(name, Category.TELECOM, City.ONLINE_HQ, r)
                        : catalog.addOnline(name, e.getKey(), r));
            }
            catalog.onlineByCategory.put(e.getKey(), List.copyOf(merchants));
        }
        return catalog;
    }

    public List<MerchantRef> all() {
        return Collections.unmodifiableList(all);
    }

    public List<MerchantRef> local(City city, Category category) {
        return localByCity.get(city.name()).getOrDefault(category, List.of());
    }

    public List<MerchantRef> online(Category category) {
        return onlineByCategory.getOrDefault(category, List.of());
    }

    /** Electricity, natural gas and water billers of a city, in that order. */
    public List<MerchantRef> utilities(City city) {
        return utilitiesByCity.get(city.name());
    }

    private MerchantRef addPos(String name, Category category, City city, Rand r) {
        String district = r.pick(city.districts());
        int terminals = r.intBetween(1, 3);
        List<String> descriptors = new ArrayList<>(terminals);
        for (int i = 0; i < terminals; i++) {
            descriptors.add(DescriptionNoiser.posDescriptor(name, district, city.abbreviation(), r));
        }
        return add(name, category, city, district, false, descriptors);
    }

    private MerchantRef addOnline(String name, Category category, Rand r) {
        List<String> descriptors = new ArrayList<>(2);
        int integrations = r.intBetween(1, 2);
        for (int i = 0; i < integrations; i++) {
            descriptors.add(DescriptionNoiser.onlineDescriptor(name, r));
        }
        return add(name, category, City.ONLINE_HQ, City.ONLINE_HQ.districts().getFirst(), true, descriptors);
    }

    private MerchantRef addBiller(String name, Category category, City city, Rand r) {
        String descriptor = DescriptionNoiser.abbreviate(name, r, 0.7);
        return add(name, category, city, city.districts().getFirst(), true, List.of(descriptor));
    }

    private MerchantRef add(String name, Category category, City city, String district, boolean online,
                            List<String> descriptors) {
        MerchantRef ref = new MerchantRef(all.size() + 1L, name, category, city, district, online,
                List.copyOf(descriptors));
        all.add(ref);
        return ref;
    }
}
