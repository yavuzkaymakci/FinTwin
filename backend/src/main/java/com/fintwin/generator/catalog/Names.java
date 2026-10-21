package com.fintwin.generator.catalog;

import com.fintwin.generator.rng.Rand;

import java.util.List;

/** Fictional names used for merchants, employers and transfer counterparties. */
public final class Names {

    public static final List<String> SURNAMES = List.of(
            "YILMAZ", "KAYA", "DEMIR", "CELIK", "SAHIN", "YILDIZ", "YILDIRIM", "OZTURK", "AYDIN", "OZDEMIR",
            "ARSLAN", "DOGAN", "KILIC", "ASLAN", "CETIN", "KARA", "KOC", "KURT", "OZKAN", "SIMSEK", "POLAT",
            "KORKMAZ", "CAKIR", "ERDEM", "GUNES", "AKSOY", "KARAKAYA", "TEKIN", "ALTUN", "KAPLAN", "BULUT",
            "GUNDOGDU", "TURAN", "AVCI", "SEN", "ACAR", "UYSAL", "KESKIN", "ERDOGDU", "COSKUN");

    public static final List<String> FIRST_NAMES = List.of(
            "AHMET", "MEHMET", "MUSTAFA", "AYSE", "FATMA", "EMINE", "ALI", "HUSEYIN", "HASAN", "ZEYNEP",
            "ELIF", "MURAT", "IBRAHIM", "OMER", "MERVE", "BURAK", "SELIN", "EMRE", "DENIZ", "CAN", "ESRA",
            "OZGE", "KEMAL", "SEVGI", "YUSUF", "HATICE", "GIZEM", "ONUR", "BERK", "DILEK", "TUGBA", "SERKAN");

    private static final List<String> COMPANY_STEMS = List.of(
            "ANADOLU", "MARMARA", "EGE", "KUZEY", "GUNEY", "ATLAS", "YILDIZ", "ZIRVE", "DORUK", "UMUT", "BEREKET",
            "INCI", "AKDENIZ", "KARADENIZ", "TOROS", "DICLE", "FIRAT", "SAFAK", "OZGUR", "MAVI", "KARTAL");

    private static final List<String> COMPANY_SECTORS = List.of(
            "TEKSTIL SANAYI VE TICARET A.S.", "LOJISTIK LIMITED SIRKETI", "GIDA SANAYI A.S.",
            "INSAAT TAAHHUT LTD. STI.", "OTOMOTIV YAN SANAYI A.S.", "MAGAZACILIK A.S.", "TURIZM ISLETMELERI A.S.",
            "PLASTIK SANAYI LTD. STI.", "ENERJI A.S.", "TEMIZLIK HIZMETLERI LTD. STI.");

    private static final List<String> TECH_SECTORS = List.of(
            "YAZILIM TEKNOLOJI A.S.", "BILISIM HIZMETLERI A.S.", "DANISMANLIK A.S.", "FINANSAL HIZMETLER A.S.");

    private static final List<String> PUBLIC_EMPLOYERS = List.of(
            "MILLI EGITIM BAKANLIGI", "SAGLIK BAKANLIGI", "BUYUKSEHIR BELEDIYESI", "ADALET BAKANLIGI",
            "DEVLET HASTANESI", "UNIVERSITE REKTORLUGU");

    private Names() {
    }

    public static String personName(Rand r) {
        return r.pick(FIRST_NAMES) + " " + r.pick(SURNAMES);
    }

    public static String company(Rand r, boolean tech) {
        return r.pick(COMPANY_STEMS) + " " + r.pick(tech ? TECH_SECTORS : COMPANY_SECTORS);
    }

    public static String publicEmployer(Rand r) {
        return r.pick(PUBLIC_EMPLOYERS);
    }
}
