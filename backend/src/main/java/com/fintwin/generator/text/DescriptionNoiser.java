package com.fintwin.generator.text;

import com.fintwin.generator.rng.Rand;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

/**
 * Produces bank-statement style transaction descriptions: upper case ASCII (Turkish letters folded), merchant
 * abbreviations, terminal/branch numbers, district and city codes, payment gateway prefixes, installment markers,
 * occasional typos and truncation — e.g. {@code "KARDESLER MKT 4123 KADIKOY IST"}.
 * <p>
 * Merchant descriptors are built once per merchant terminal (stable, like a real acquirer setup); per-transaction
 * noise is then applied on top.
 */
public final class DescriptionNoiser {

    /** Card schemes cap the merchant name/location field; statements show roughly this much. */
    public static final int POS_MAX_LENGTH = 40;
    public static final int MAX_LENGTH = 64;

    private static final Map<String, String[]> ABBREVIATIONS = Map.ofEntries(
            Map.entry("MARKET", new String[]{"MKT", "MRKT", "MARKET", "MARKET"}),
            Map.entry("SUPERMARKET", new String[]{"SPRMKT", "SUPERMRKT", "SUPERMARKET"}),
            Map.entry("AKARYAKIT", new String[]{"AKARYKT", "AKRYKT", "AKARYAKIT"}),
            Map.entry("PETROL", new String[]{"PET", "PETROL", "PETROL"}),
            Map.entry("RESTORAN", new String[]{"REST", "RESTORAN"}),
            Map.entry("LOKANTASI", new String[]{"LOK", "LOKANTA", "LOKANTASI"}),
            Map.entry("ECZANESI", new String[]{"ECZ", "ECZA", "ECZANESI"}),
            Map.entry("HASTANESI", new String[]{"HAST", "HST", "HASTANESI"}),
            Map.entry("POLIKLINIGI", new String[]{"POLK", "POLIKL", "POLIKLINIGI"}),
            Map.entry("ELEKTRONIK", new String[]{"ELKTRNK", "ELEKTR", "ELEKTRONIK"}),
            Map.entry("GIYIM", new String[]{"GIY", "GIYIM"}),
            Map.entry("AYAKKABI", new String[]{"AYKB", "AYAKKABI"}),
            Map.entry("KIRTASIYE", new String[]{"KIRTAS", "KIRTASIYE"}),
            Map.entry("TURIZM", new String[]{"TUR", "TURZ", "TURIZM"}),
            Map.entry("HAVAYOLLARI", new String[]{"HVYL", "HAVAYOL", "HAVAYOLLARI"}),
            Map.entry("TICARET", new String[]{"TIC", "TIC."}),
            Map.entry("SANAYI", new String[]{"SAN", "SAN."}),
            Map.entry("LIMITED", new String[]{"LTD"}),
            Map.entry("SIRKETI", new String[]{"STI"}),
            Map.entry("MAGAZACILIK", new String[]{"MAG", "MGZ"}),
            Map.entry("DAGITIM", new String[]{"DAG", "DGTM"}),
            Map.entry("PERAKENDE", new String[]{"PRK", "PERAK"}),
            Map.entry("ELEKTRIK", new String[]{"ELEK", "ELKT", "ELEKTRIK"}),
            Map.entry("DOGALGAZ", new String[]{"DGAZ", "DOGALGAZ"}),
            Map.entry("ODEMESI", new String[]{"OD", "ODM", "ODEMESI"}),
            Map.entry("ODEME", new String[]{"OD", "ODM", "ODEME"}));

    private static final String[] ONLINE_GATEWAYS = {"IYZICO*", "PAYTR *", "PARAM*", "SANAL POS ", "IPARA*"};

    private DescriptionNoiser() {
    }

    /** Upper-cases and folds Turkish letters to ASCII; strips characters statements cannot print. */
    public static String fold(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            sb.append(switch (c) {
                case 'ç', 'Ç' -> 'C';
                case 'ğ', 'Ğ' -> 'G';
                case 'ı', 'İ' -> 'I';
                case 'ö', 'Ö' -> 'O';
                case 'ş', 'Ş' -> 'S';
                case 'ü', 'Ü' -> 'U';
                default -> c;
            });
        }
        String upper = sb.toString().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 *./:()#'-]", " ");
        return upper.replaceAll(" {2,}", " ").trim();
    }

    /** Randomly replaces known words with statement abbreviations. */
    public static String abbreviate(String text, Rand r, double probability) {
        String[] words = fold(text).split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            String[] options = ABBREVIATIONS.get(word);
            if (options != null && r.chance(probability)) {
                word = r.pick(options);
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(word);
        }
        return sb.toString();
    }

    /** One stable descriptor of a physical POS terminal. */
    public static String posDescriptor(String merchantName, String district, String cityAbbreviation, Rand r) {
        String name = abbreviate(merchantName, r, 0.6);
        String terminal = r.digits(4);
        String descriptor = switch (r.intBetween(0, 4)) {
            case 0 -> name + " " + terminal + " " + district + " " + cityAbbreviation;
            case 1 -> name + " " + district + " " + cityAbbreviation + " TR";
            case 2 -> name + " " + district + "/" + cityAbbreviation;
            case 3 -> name + " SB." + r.digits(3) + " " + cityAbbreviation;
            default -> name + " " + district + " " + terminal;
        };
        return truncate(fold(descriptor), POS_MAX_LENGTH);
    }

    /** One stable descriptor of an online merchant (payment gateway or web address forms). */
    public static String onlineDescriptor(String merchantName, Rand r) {
        String name = abbreviate(merchantName, r, 0.4);
        String compact = name.replace(" ", "");
        String descriptor = switch (r.intBetween(0, 5)) {
            case 0 -> r.pick(ONLINE_GATEWAYS) + name;
            case 1 -> r.pick(ONLINE_GATEWAYS) + name + " ISTANBUL";
            case 2 -> compact.endsWith(".COM") ? compact + ".TR" : compact + ".COM.TR";
            case 3 -> "WWW." + compact.replace(".COM", "") + ".COM IST";
            case 4 -> name + " INTERNET ODEME";
            default -> name + " ONLINE IST TR";
        };
        return truncate(fold(descriptor), POS_MAX_LENGTH);
    }

    /** Per-transaction rendering of a card purchase. */
    public static String purchase(String descriptor, int installments, Rand r) {
        String text = r.chance(0.03) ? typo(descriptor, r) : descriptor;
        if (installments > 1) {
            text = text + switch (r.intBetween(0, 2)) {
                case 0 -> " " + installments + " TAKSIT";
                case 1 -> " TKS:" + installments;
                default -> " (" + installments + " TKS)";
            };
        }
        return truncate(text, MAX_LENGTH);
    }

    public static String atmWithdrawal(String district, String cityAbbreviation, boolean cashAdvance, Rand r) {
        String text = cashAdvance
                ? r.pick(new String[]{"NAKIT AVANS ATM ", "KK NAKIT AVANS ", "ATM NKT AVANS "}) + r.digits(5)
                + " " + district
                : r.pick(new String[]{"ATM PARA CEKME ", "ATM NAKIT CEKIM ", "BNKMATIK PARA CEKME ", "ATM CEKIM "})
                + r.digits(5) + " " + district + " " + cityAbbreviation;
        return truncate(fold(text), MAX_LENGTH);
    }

    public static String salary(String employer, YearMonth month, Rand r) {
        String period = String.format("%02d/%d", month.getMonthValue(), month.getYear());
        String text = switch (r.intBetween(0, 3)) {
            case 0 -> "MAAS ODEMESI " + employer;
            case 1 -> abbreviate(employer, r, 0.8) + " MAAS " + period;
            case 2 -> "EFT GELEN " + abbreviate(employer, r, 0.8) + " MAAS";
            default -> "MAAS " + period + " " + abbreviate(employer, r, 0.8);
        };
        return truncate(fold(text), MAX_LENGTH);
    }

    public static String pension(YearMonth month, Rand r) {
        String text = switch (r.intBetween(0, 2)) {
            case 0 -> "SGK EMEKLI AYLIGI " + String.format("%02d/%d", month.getMonthValue(), month.getYear());
            case 1 -> "EMEKLI MAAS ODEMESI SGK";
            default -> "SOSYAL GUVENLIK KURUMU EMK AYLIK";
        };
        return truncate(fold(text), MAX_LENGTH);
    }

    public static String scholarship(Rand r) {
        return r.pick(new String[]{"KYK BURS ODEMESI", "KYK KREDI/BURS", "GSB KYK OGRENIM KREDISI"});
    }

    public static String incomingTransfer(String counterparty, String purpose, Rand r) {
        String text = switch (r.intBetween(0, 2)) {
            case 0 -> "FAST GELEN " + counterparty + " " + purpose;
            case 1 -> "EFT-" + counterparty + "-" + purpose;
            default -> "HAVALE " + counterparty + " " + r.digits(6);
        };
        return truncate(fold(text), MAX_LENGTH);
    }

    public static String outgoingTransfer(String counterparty, String purpose, Rand r) {
        String text = switch (r.intBetween(0, 2)) {
            case 0 -> "FAST " + counterparty + " " + purpose;
            case 1 -> "EFT GIDEN " + counterparty + " " + purpose;
            default -> "INT.BANK. " + counterparty + " " + r.digits(6);
        };
        return truncate(fold(text), MAX_LENGTH);
    }

    public static String rent(String landlord, YearMonth month, Rand r) {
        String monthName = month.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("tr"));
        String text = switch (r.intBetween(0, 3)) {
            case 0 -> "FAST " + landlord + " KIRA";
            case 1 -> "EFT " + landlord + " " + monthName + " KIRA BEDELI";
            case 2 -> "KIRA ODEMESI " + landlord;
            default -> "FAST " + landlord + " EV KIRASI " + monthName;
        };
        return truncate(fold(text), MAX_LENGTH);
    }

    public static String bill(String billerDescriptor, String subscriberNo, Rand r) {
        String text = switch (r.intBetween(0, 2)) {
            case 0 -> "OTOMATIK ODEME " + billerDescriptor + " " + subscriberNo;
            case 1 -> abbreviate(billerDescriptor, r, 0.8) + " FATURA TAHSILATI";
            default -> "FATURA " + abbreviate(billerDescriptor, r, 0.8) + " ABN:" + subscriberNo;
        };
        return truncate(fold(text), MAX_LENGTH);
    }

    public static String loanInstallment(String loanNo, int installmentNo, int term, Rand r) {
        String text = switch (r.intBetween(0, 2)) {
            case 0 -> "KREDI TAKSIT TAHSILATI " + loanNo;
            case 1 -> "TKST " + installmentNo + "/" + term + " KRD " + loanNo;
            default -> "KREDI ODEME " + loanNo + " TAKSIT " + installmentNo;
        };
        return truncate(fold(text), MAX_LENGTH);
    }

    public static String loanDisbursement(String loanNo, boolean mortgage, Rand r) {
        String product = mortgage ? "KONUT KREDISI" : r.pick(new String[]{"IHTIYAC KREDISI", "IHT. KRD", "TUKETICI KRD"});
        return truncate(fold("KREDI KULLANDIRIM " + product + " " + loanNo), MAX_LENGTH);
    }

    public static String cardPaymentFromAccount(String cardLast4, boolean automatic, Rand r) {
        String text = automatic
                ? r.pick(new String[]{"KK OTOMATIK ODEME ", "KART BORCU OTOMATIK TAHSILAT "}) + "****" + cardLast4
                : r.pick(new String[]{"KREDI KARTI ODEMESI ", "KK BORC ODEME ", "KART ODEME "}) + "****" + cardLast4;
        return truncate(fold(text), MAX_LENGTH);
    }

    public static String cardPaymentReceived(Rand r) {
        return r.pick(new String[]{"ODEMENIZ ICIN TESEKKURLER", "HESAPTAN ODEME", "ODEME - TESEKKUR EDERIZ"});
    }

    public static String fxPurchase(boolean usdSide, Rand r) {
        return usdSide
                ? r.pick(new String[]{"DOVIZ ALIS USD", "USD ALIS ISLEMI", "DVZ ALIM USD/TRY"})
                : r.pick(new String[]{"DOVIZ ALIS TRY KARSILIGI", "USD ALIS TL TUTARI", "DVZ ALIM TL"});
    }

    public static String depositTransfer(boolean toDeposit, Rand r) {
        return toDeposit
                ? r.pick(new String[]{"VADELI HESABA VIRMAN", "HESAPLAR ARASI VIRMAN VADELI", "BIRIKIM AKTARIM"})
                : r.pick(new String[]{"VADELI HESAPTAN VIRMAN", "VADELI BOZDURMA", "HESAPLAR ARASI VIRMAN"});
    }

    public static String depositInterest(Rand r) {
        return r.pick(new String[]{"VADELI HESAP FAIZ TAHAKKUKU", "MEVDUAT FAIZI NET", "VADE SONU FAIZ ODEMESI"});
    }

    public static String cardInterest(boolean late, Rand r) {
        return late
                ? r.pick(new String[]{"GECIKME FAIZI", "GECIKME FAIZI VE BSMV", "KK GECIKME FAIZ"})
                : r.pick(new String[]{"AKDI FAIZ", "KART FAIZI", "ALISVERIS FAIZI"});
    }

    public static String accountClosing(Rand r) {
        return r.pick(new String[]{"HESAP KAPAMA FAST GIDEN", "HESAP KAPANIS BAKIYE TRANSFERI", "EFT HESAP KAPAMA"});
    }

    static String typo(String text, Rand r) {
        if (text.length() < 6) {
            return text;
        }
        int i = r.intBetween(1, text.length() - 2);
        if (!Character.isLetter(text.charAt(i))) {
            return text;
        }
        return r.chance(0.5)
                ? text.substring(0, i) + text.substring(i + 1)
                : text.substring(0, i) + text.charAt(i + 1) + text.charAt(i) + text.substring(i + 2);
    }

    /** Caps free-form descriptions at {@link #MAX_LENGTH}. */
    public static String truncateDescription(String text) {
        return truncate(text, MAX_LENGTH);
    }

    static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength).trim();
    }
}
