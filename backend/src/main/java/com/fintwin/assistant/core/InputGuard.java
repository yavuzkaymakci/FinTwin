package com.fintwin.assistant.core;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cheap first line against prompt injection: messages that try to override the instructions, reveal the system
 * prompt or (for the coach) reach another customer's data are refused before any model call. The tools enforce the
 * same boundaries independently (identity comes from {@code CurrentUser}, never from arguments), so this guard is
 * defence in depth, not the only control.
 */
public final class InputGuard {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("(önceki|yukarıdaki|tüm|bütün|verilen)\\s+(talimat|kural|komut|yönerge)\\w*\\s+"
                    + "(\\w+\\s+)?(unut|yok\\s*say|görmezden\\s*gel|geçersiz|iptal)", FLAGS),
            Pattern.compile("ignore\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above|earlier)\\s+(instructions|rules)",
                    FLAGS),
            Pattern.compile("(system|sistem)\\s*(prompt|mesaj|talimat)", FLAGS),
            Pattern.compile("\\b(jailbreak|developer\\s+mode|dan\\s+mode)\\b", FLAGS),
            Pattern.compile("(artık|bundan\\s+sonra)\\s+(sen\\s+)?(bir\\s+)?(admin|yönetici|geliştirici|sistem)", FLAGS));

    private static final List<Pattern> OTHER_CUSTOMER = List.of(
            Pattern.compile("(başka|diğer|öteki)\\s+(bir\\s+)?(müşteri|kullanıcı|kişi)", FLAGS),
            Pattern.compile("müşteri\\s*(no|numarası|numaralı|id|kimliği)?\\s*[:#]?\\s*\\d+", FLAGS),
            Pattern.compile("customer[\\s_-]*id", FLAGS),
            Pattern.compile("x-demo-", FLAGS));

    public static final String REFUSAL_INJECTION = "Bu isteği yerine getiremiyorum: talimatlarımı değiştirmeye "
            + "veya sistem bilgilerini açığa çıkarmaya yönelik görünüyor. Size yardımcı olabileceğim konularla ilgili "
            + "sorunuzu yeniden sorabilirsiniz.";
    public static final String REFUSAL_OTHER_CUSTOMER = "Yalnızca sizin kendi hesap ve ikiz verilerinizle "
            + "çalışabilirim; başka müşterilere ait bilgilere erişemem. Kendi bütçeniz veya hedefleriniz hakkında "
            + "yardımcı olabilirim.";

    public Optional<String> check(AssistantType type, String message) {
        String text = message.toLowerCase(Locale.forLanguageTag("tr"));
        if (INJECTION.stream().anyMatch(p -> p.matcher(text).find())) {
            return Optional.of(REFUSAL_INJECTION);
        }
        if (type == AssistantType.COACH && OTHER_CUSTOMER.stream().anyMatch(p -> p.matcher(text).find())) {
            return Optional.of(REFUSAL_OTHER_CUSTOMER);
        }
        return Optional.empty();
    }
}
