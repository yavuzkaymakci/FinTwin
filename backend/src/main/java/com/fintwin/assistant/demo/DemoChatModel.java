package com.fintwin.assistant.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripted chat model of the {@code ai-demo} profile: recognises the example questions, calls the real tools and
 * writes prepared Turkish answers whose numbers come from the tool results. It lets the UI show tool tags, the
 * confirmation card and run links without an LLM. Answers start with a demo-mode note; anything it does not
 * recognise gets a list of the supported questions.
 */
public class DemoChatModel implements ChatModel {

    static final String MODEL = "fintwin-ai-demo";
    private static final Locale TR = Locale.forLanguageTag("tr");
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Map<String, String> CATEGORY_TR = Map.ofEntries(
            Map.entry("GROCERY", "market"), Map.entry("RESTAURANT", "restoran"), Map.entry("ENTERTAINMENT", "eğlence"),
            Map.entry("TRAVEL", "seyahat"), Map.entry("CLOTHING", "giyim"), Map.entry("ELECTRONICS", "elektronik"),
            Map.entry("ECOMMERCE", "e-ticaret"), Map.entry("UTILITIES", "faturalar"), Map.entry("TELECOM", "iletişim"),
            Map.entry("RENT", "kira"), Map.entry("HEALTH", "sağlık"), Map.entry("TRANSPORT", "ulaşım"),
            Map.entry("FUEL", "akaryakıt"), Map.entry("EDUCATION", "eğitim"), Map.entry("CASH_WITHDRAWAL", "nakit çekim"));

    private final ObjectMapper json;

    public DemoChatModel(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Turn turn = Turn.of(prompt);
        Step step = turn.coach ? coach(turn) : scenario(turn);
        AssistantMessage message = step.tool != null
                ? AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                "demo-" + UUID.randomUUID(), "function", step.tool, step.arguments))).build()
                : new AssistantMessage(step.text);
        return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder().model(MODEL).build());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatResponse full = call(prompt);
        AssistantMessage out = full.getResult().getOutput();
        if (out.hasToolCalls()) {
            return Flux.just(full);
        }
        List<ChatResponse> chunks = new ArrayList<>();
        for (String word : out.getText().split("(?<=\\s)")) {
            chunks.add(new ChatResponse(List.of(new Generation(new AssistantMessage(word))), full.getMetadata()));
        }
        return Flux.fromIterable(chunks);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().model(MODEL).build();
    }

    // ------------------------------------------------------------------ scenario assistant

    private Step scenario(Turn t) {
        String q = t.question;
        Matcher pendingId = Pattern.compile("Senaryo ID: (\\d+)").matcher(t.previousAnswer);
        boolean pending = pendingId.find() && t.previousAnswer.contains("onaylıyor musunuz");
        if (pending && matches(q, "\\b(hayır|vazgeç|iptal|istemiyorum)")) {
            return text("Tamam, simülasyonu başlatmadım. Parametreleri değiştirmek isterseniz yeni değerleri yazmanız "
                    + "yeterli.");
        }
        if (pending && matches(q, "\\b(evet|onay|başlat|çalıştır|tamam|olur)")) {
            JsonNode started = t.result("startSimulation");
            if (started == null) {
                return tool("startSimulation", Map.of("scenarioId", Long.parseLong(pendingId.group(1))));
            }
            if ("STARTED".equals(started.path("status").asText())) {
                long run = started.path("runId").asLong();
                return text("Simülasyon başladı: **Koşu #" + run + "** (" + started.path("scenarioCode").asText()
                        + ", baz senaryo ile karşılaştırmalı). Koşu birkaç dakika sürer; ilerlemeyi senaryo sonuç "
                        + "ekranından izleyebilirsiniz. Bittiğinde \"Koşu " + run + " sonuçlarını özetle\" diye "
                        + "sorabilirsiniz.");
            }
            return text("Onayınızı bu turda alamadım; simülasyon başlatılmadı.");
        }
        Long run = number(q, "(?:koşu\\s*#?\\s*(\\d+))|(?:(\\d+)\\s*(?:numaralı|no\\.?|nolu)\\s*koşu)");
        List<Long> runs = numbers(q, "#?(\\d+)");
        if (matches(q, "karşılaştır") && runs.size() >= 2) {
            JsonNode r = t.result("compareRuns");
            return r == null ? tool("compareRuns", Map.of("runIds", runs.subList(0, Math.min(runs.size(), 4))))
                    : text(compareText(r));
        }
        if (run != null && matches(q, "segment")) {
            JsonNode r = t.result("getSegmentImpact");
            return r == null ? tool("getSegmentImpact", Map.of("runId", run)) : text(segmentText(r));
        }
        if (run != null && matches(q, "etkilenen|müşteri")) {
            JsonNode r = t.result("getTopAffectedCustomers");
            return r == null ? tool("getTopAffectedCustomers", Map.of("runId", run, "limit", 3)) : text(topText(r));
        }
        if (run != null) {
            JsonNode r = t.result("getRunResults");
            return r == null ? tool("getRunResults", Map.of("runId", run)) : text(resultText(r));
        }
        Double usd = decimal(q, "(?:dolar|kur|usd)[^%]{0,40}?%\\s*(\\d+(?:[.,]\\d+)?)");
        Double inflation = decimal(q, "enflasyon[^%]{0,40}?%\\s*(\\d+(?:[.,]\\d+)?)");
        Long bp = number(q, "(\\d+)\\s*baz\\s*puan");
        if (usd != null || inflation != null || bp != null) {
            JsonNode created = t.result("createScenario");
            if (created == null) {
                return tool("createScenario", scenarioArguments(q, usd, inflation, bp));
            }
            if (created.has("error")) {
                return text("Senaryoyu oluşturamadım: " + created.path("violations").toString());
            }
            JsonNode confirm = t.result("startSimulation");
            if (confirm == null) {
                return tool("startSimulation", Map.of("scenarioId", created.path("scenarioId").asLong()));
            }
            return text(confirmationText(confirm));
        }
        String preset = preset(q);
        if (preset != null && matches(q, "çalıştır|başlat|koştur|simüle")) {
            JsonNode confirm = t.result("startSimulation");
            if (confirm != null) {
                return text(confirmationText(confirm));
            }
            JsonNode list = t.result("listScenarios");
            if (list == null) {
                return tool("listScenarios", Map.of());
            }
            for (JsonNode s : list) {
                if (preset.equals(s.path("code").asText())) {
                    return tool("startSimulation", Map.of("scenarioId", s.path("scenarioId").asLong()));
                }
            }
            return text("Bu hazır senaryoyu bulamadım.");
        }
        if (matches(q, "senaryo")) {
            JsonNode list = t.result("listScenarios");
            return list == null ? tool("listScenarios", Map.of()) : text(listText(list));
        }
        if (matches(q, "portföy|risk durumu|risk özeti|ortalama pd")) {
            JsonNode r = t.result("getPortfolioRiskSummary");
            return r == null ? tool("getPortfolioRiskSummary", Map.of()) : text(portfolioText(r));
        }
        return text("""
                Demo modunda şu örnek soruları yanıtlayabiliyorum (yanıtlar önceden hazırlanmış, araçlar gerçek):
                - "Hangi hazır senaryolar var?"
                - "Portföyün güncel risk durumu nedir?"
                - "Dolar %40 artarsa ve enflasyon 6 ay boyunca aylık %4 olursa ne olur?"
                - "Kur şoku senaryosunu çalıştır"
                - "Koşu 2 sonuçlarını özetle", "Koşu 1 ve 2'yi karşılaştır"
                - "Koşu 2'de hangi segment en çok etkilendi?", "Koşu 2'de en çok etkilenen müşteriler"
                Gerçek bir yapay zekâ sağlayıcısı (Anthropic veya Ollama) bağlandığında serbest sorular da sorulabilir.""");
    }

    private Map<String, Object> scenarioArguments(String q, Double usd, Double inflation, Long bp) {
        Long months = number(q, "(\\d+)\\s*ay");
        List<Double> path = new ArrayList<>();
        if (inflation != null) {
            int n = months == null ? 12 : (int) Math.min(Math.max(months, 1), 12);
            for (int i = 0; i < n; i++) {
                path.add(inflation);
            }
            if (n < 12) {
                path.add(2.0);
            }
        } else {
            path.add(2.0);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("horizonMonths", 12);
        parameters.put("usdTryChangePct", usd == null ? 15.0 : usd);
        parameters.put("policyRateChangeBp", bp == null ? 0 : bp);
        parameters.put("monthlyInflationPct", path);
        parameters.put("wageIndexationPct", 90);
        parameters.put("wageAdjustmentLagMonths", 6);
        parameters.put("shockProfile", usd != null || bp != null ? "IMMEDIATE" : "GRADUAL");
        parameters.put("shockRampMonths", 6);
        parameters.put("monteCarloPaths", 100);
        parameters.put("seed", 42);
        StringBuilder name = new StringBuilder();
        if (usd != null) {
            name.append("Dolar +%").append(fmt(usd, 0));
        }
        if (inflation != null) {
            name.append(name.isEmpty() ? "" : ", ").append("enflasyon aylık %").append(fmt(inflation, 1));
        }
        if (bp != null) {
            name.append(name.isEmpty() ? "" : ", ").append("faiz +").append(bp).append(" bp");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("code", "DEMO_" + Long.toString(Instant.now().getEpochSecond(), 36).toUpperCase(Locale.ROOT));
        args.put("name", name.toString());
        args.put("description", "Demo asistanı tarafından sorudan oluşturuldu: " + q);
        args.put("parameters", parameters);
        return args;
    }

    private String confirmationText(JsonNode r) {
        if (!"CONFIRMATION_REQUIRED".equals(r.path("status").asText())) {
            return "Simülasyonu başlatamadım: " + r.path("message").asText(r.toString());
        }
        JsonNode s = r.path("scenario");
        JsonNode p = s.path("parameters");
        List<String> inflation = new ArrayList<>();
        p.path("monthlyInflationPct").forEach(v -> inflation.add("%" + fmt(v.asDouble(), 1)));
        return "Şu senaryoyla bir simülasyon başlatmak üzereyim:\n\n"
                + "- **Senaryo:** " + s.path("name").asText() + " (" + s.path("code").asText() + ")\n"
                + "- **USD/TRY değişimi:** %" + fmt(p.path("usdTryChangePct").asDouble(), 1) + " ("
                + p.path("shockProfile").asText() + ")\n"
                + "- **Politika faizi:** " + p.path("policyRateChangeBp").asInt() + " baz puan\n"
                + "- **Aylık enflasyon patikası:** " + String.join(", ", inflation) + "\n"
                + "- **Ücret ayarlaması:** enflasyonun %" + fmt(p.path("wageIndexationPct").asDouble(), 0) + "'i, "
                + p.path("wageAdjustmentLagMonths").asInt() + " ayda bir\n"
                + "- **Ufuk / yol sayısı:** " + p.path("horizonMonths").asInt() + " ay, "
                + p.path("monteCarloPaths").asInt() + " Monte Carlo yolu\n"
                + "- **Baz senaryo:** " + (r.path("baseline").isTextual() ? "BASE (hazır)" : r.path("baseline")
                .path("code").asText()) + "\n\n"
                + "Senaryo ID: " + s.path("scenarioId").asLong() + ". Simülasyon birkaç dakika sürer; onaylıyor "
                + "musunuz?";
    }

    private String resultText(JsonNode r) {
        if (!r.has("portfolio")) {
            return "Koşu #" + r.path("runId").asLong() + " henüz tamamlanmadı (durum: " + r.path("status").asText()
                    + ", ilerleme %" + fmt(r.path("progressPct").asDouble(), 0) + ").";
        }
        JsonNode p = r.path("portfolio");
        JsonNode dr = p.path("defaultRate");
        StringBuilder out = new StringBuilder("**Koşu #" + r.path("runId").asLong() + " — " + r.path("scenarioName")
                .asText() + "**\n\n");
        out.append("1. **Özet:** ").append(p.path("creditCustomers").asLong()).append(" kredili müşteride ")
                .append(r.path("horizonMonths").asInt()).append(" aylık temerrüt oranı ortalama ")
                .append(pct(dr.path("mean"))).append(".\n");
        out.append("2. **Bulgular:**\n   - Temerrüt oranı P5–P95 aralığı: ").append(pct(dr.path("p5"))).append(" – ")
                .append(pct(dr.path("p95"))).append("\n   - Beklenen kayıp: ").append(money(p.path("expectedLoss")))
                .append(", P99 kayıp: ").append(money(p.path("lossP99"))).append("\n");
        JsonNode delta = r.path("deltaVsBaseline");
        if (!delta.isMissingNode() && !delta.isNull()) {
            out.append("   - Baz senaryoya göre temerrüt farkı: ").append(points(delta.path("defaultRate").path("mean")))
                    .append(", beklenen kayıp farkı: ").append(money(delta.path("loss").path("mean"))).append("\n");
        }
        out.append("3. **Not:** Sonuçlar portföy ve segment düzeyindedir; ürün (kart/kredi) kırılımı yoktur.\n");
        out.append("4. **Koşu ID:** ").append(r.path("runId").asLong());
        return out.toString();
    }

    private String compareText(JsonNode r) {
        StringBuilder out = new StringBuilder("**Koşu karşılaştırması**\n\n| Koşu | Senaryo | Temerrüt | Beklenen kayıp "
                + "| P99 kayıp |\n|---|---|---|---|---|\n");
        for (JsonNode row : r.path("runs")) {
            out.append("| #").append(row.path("runId").asLong()).append(" | ").append(row.path("scenarioName").asText())
                    .append(" | ").append(pct(row.path("defaultRate").path("mean"))).append(" | ")
                    .append(money(row.path("expectedLoss"))).append(" | ").append(money(row.path("lossP99")))
                    .append(" |\n");
        }
        JsonNode runs = r.path("runs");
        if (runs.size() >= 2) {
            JsonNode last = runs.get(runs.size() - 1);
            out.append("\nİlk koşuya göre son koşunun temerrüt farkı ").append(points(last.path("defaultRateDeltaVsFirst")))
                    .append(", beklenen kayıp farkı ").append(money(last.path("expectedLossDeltaVsFirst"))).append(".");
        }
        return out.toString();
    }

    private String segmentText(JsonNode r) {
        if (!r.has("segments")) {
            return "Koşu henüz tamamlanmadı (durum: " + r.path("status").asText() + ").";
        }
        JsonNode worst = null;
        StringBuilder out = new StringBuilder("**Koşu #" + r.path("runId").asLong() + " segment etkisi**\n\n"
                + "| Segment | Temerrüt | Baz farkı | Beklenen kayıp |\n|---|---|---|---|\n");
        for (JsonNode s : r.path("segments")) {
            out.append("| ").append(s.path("segment").asText()).append(" | ").append(pct(s.path("defaultRate").path("mean")))
                    .append(" | ").append(s.has("defaultRateDeltaVsBaseline") ? points(s.path("defaultRateDeltaVsBaseline"))
                            : "–").append(" | ").append(money(s.path("expectedLoss"))).append(" |\n");
            if (worst == null || s.path("defaultRateDeltaVsBaseline").asDouble()
                    > worst.path("defaultRateDeltaVsBaseline").asDouble()) {
                worst = s;
            }
        }
        if (worst != null && worst.has("defaultRateDeltaVsBaseline")) {
            out.append("\nBaz senaryoya göre en çok etkilenen segment **").append(worst.path("segment").asText())
                    .append("** (").append(points(worst.path("defaultRateDeltaVsBaseline"))).append(").");
        }
        return out.toString();
    }

    private String topText(JsonNode r) {
        StringBuilder out = new StringBuilder("**Koşu #" + r.path("runId").asLong()
                + " — PD'si en çok artan müşteriler**\n\n");
        for (JsonNode c : r.path("customers")) {
            out.append("- **Müşteri ").append(c.path("customerId").asLong()).append("** (").append(c.path("segment")
                    .asText()).append("): PD ").append(pct(c.path("pdStart"))).append(" → ").append(pct(c.path("pdHorizon")))
                    .append("\n");
            for (JsonNode reason : c.path("reasons")) {
                out.append("  - ").append(reason.asText()).append("\n");
            }
        }
        return out.toString();
    }

    private String listText(JsonNode list) {
        StringBuilder out = new StringBuilder("Kayıtlı senaryolar:\n\n");
        for (JsonNode s : list) {
            out.append("- **").append(s.path("name").asText()).append("** (").append(s.path("code").asText())
                    .append(", ID ").append(s.path("scenarioId").asLong()).append(")").append(s.path("preset")
                            .asBoolean() ? " — hazır" : "").append("\n");
        }
        return out.append("\nBirini çalıştırmak için örneğin \"Kur şoku senaryosunu çalıştır\" yazabilirsiniz.").toString();
    }

    private String portfolioText(JsonNode r) {
        if (r.has("error")) {
            return "Portföy henüz skorlanmamış; önce toplu risk skorlaması çalıştırılmalı.";
        }
        StringBuilder out = new StringBuilder("**Güncel portföy risk özeti**\n\n- Skorlanan müşteri: "
                + r.path("customers").asLong() + "\n- Ortalama 6 aylık PD: " + pct(r.path("meanPd"))
                + "\n- Ortalama churn olasılığı: " + pct(r.path("meanChurn")) + "\n\n**Segmentler:**\n");
        JsonNode worst = null;
        for (JsonNode s : r.path("segments")) {
            out.append("- ").append(s.path("segment").asText()).append(": PD ").append(pct(s.path("meanPd")))
                    .append(", churn ").append(pct(s.path("meanChurn"))).append("\n");
            if (worst == null || s.path("meanPd").asDouble() > worst.path("meanPd").asDouble()) {
                worst = s;
            }
        }
        if (worst != null) {
            out.append("\nEn riskli segment **").append(worst.path("segment").asText()).append("** (ortalama PD ")
                    .append(pct(worst.path("meanPd"))).append(").");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ customer coach

    private Step coach(Turn t) {
        String q = t.question;
        if (matches(q, "yatırım|hisse|fon\\b|kripto|altın|borsa|döviz al")) {
            return text("Yatırım tavsiyesi veremiyorum; bunun için lisanslı bir finansal danışmana başvurabilirsiniz. "
                    + "Bütçeniz, harcamalarınız ve birikim hedefleriniz konusunda ise yardımcı olabilirim.");
        }
        if (matches(q, "biriktir|hedef|peşinat|birikim")) {
            JsonNode r = t.result("simulateSavingsGoal");
            return r == null ? tool("simulateSavingsGoal", goalArguments(q)) : text(goalText(r));
        }
        if (matches(q, "harcama|kategori|nereye")) {
            JsonNode r = t.result("getMySpendingBreakdown");
            return r == null ? tool("getMySpendingBreakdown", Map.of()) : text(spendingText(r));
        }
        if (matches(q, "tahmin|önümüzdeki|gelecek|ileride")) {
            JsonNode r = t.result("getMyCashFlowForecast");
            return r == null ? tool("getMyCashFlowForecast", Map.of()) : text(forecastText(r));
        }
        if (matches(q, "durum|özet|nasıl|borç|bakiye|gelir")) {
            JsonNode r = t.result("getMyTwinSummary");
            return r == null ? tool("getMyTwinSummary", Map.of()) : text(twinText(r));
        }
        return text("""
                Demo modunda şu örnek soruları yanıtlayabiliyorum (yanıtlar önceden hazırlanmış, veriler sizin):
                - "Finansal durumum nasıl görünüyor?"
                - "Son 3 ayda en çok neye harcama yaptım?"
                - "Önümüzdeki aylarda bakiyem nasıl değişebilir?"
                - "3 yıl içinde 500 bin TL peşinat biriktirmek istiyorum, restoranı yarıya indirirsem ne değişir?\"""");
    }

    private Map<String, Object> goalArguments(String q) {
        Double amount = amount(q);
        Long years = number(q, "(\\d+)\\s*yıl");
        Long months = number(q, "(\\d+)\\s*ay");
        int horizon = years != null ? (int) (years * 12) : months != null ? months.intValue() : 36;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("targetAmount", amount == null ? 500_000.0 : amount);
        args.put("months", Math.max(1, Math.min(horizon, 120)));
        Double extra = decimal(q, "ayda\\s*(\\d+(?:\\.\\d{3})*(?:,\\d+)?)\\s*(?:tl|lira)?\\s*(?:fazla|ek|daha)");
        if (extra != null) {
            args.put("extraMonthlySaving", extra);
        }
        Double cut = matches(q, "yarı") ? Double.valueOf(50) : decimal(q, "%\\s*(\\d+)\\s*(?:azalt|kıs|indir)");
        if (cut != null) {
            ArrayNode cuts = json.createArrayNode();
            Map<String, String> keywords = Map.of("restoran", "RESTAURANT", "eğlence", "ENTERTAINMENT", "seyahat",
                    "TRAVEL", "giyim", "CLOTHING", "e-ticaret", "ECOMMERCE", "elektronik", "ELECTRONICS");
            keywords.forEach((word, category) -> {
                if (q.contains(word)) {
                    ObjectNode c = cuts.addObject();
                    c.put("category", category);
                    c.put("cutPct", cut);
                }
            });
            if (!cuts.isEmpty()) {
                args.put("categoryCuts", cuts);
            }
        }
        return args;
    }

    private String goalText(JsonNode r) {
        if (r.has("error")) {
            return "Hedef simülasyonunu şu an çalıştıramadım (" + r.path("message").asText() + ").";
        }
        StringBuilder out = new StringBuilder("**Birikim hedefi: " + money(r.path("targetAmount")) + ", "
                + r.path("months").asInt() + " ay**\n\n");
        out.append("- Hiçbir değişiklik yapmazsanız hedefe ulaşma olasılığı: **")
                .append(pct(r.path("probabilityWithoutChanges"))).append("**\n");
        if (r.path("categoryCuts").size() > 0 || r.path("extraMonthlySaving").asDouble() > 0) {
            out.append("- Önerdiğiniz değişikliklerle: **").append(pct(r.path("probabilityWithChanges"))).append("**");
            if (r.path("monthlySavingFromCuts").asDouble() > 0) {
                out.append(" (kesintiler ayda yaklaşık ").append(money(r.path("monthlySavingFromCuts")))
                        .append(" kazandırıyor)");
            }
            out.append("\n");
        }
        if (!r.path("requiredExtraMonthlySaving").isNull()) {
            out.append("- Hedefe %").append(fmt(r.path("confidence").asDouble() * 100, 0))
                    .append(" olasılıkla ulaşmak için gereken aylık ek birikim: **")
                    .append(money(r.path("requiredExtraMonthlySaving"))).append("**\n");
        }
        JsonNode band = r.path("balanceAtHorizonWithChanges");
        out.append("- Dönem sonunda beklenen birikim bandı: ").append(money(band.path("p10"))).append(" – ")
                .append(money(band.path("p90"))).append(" (ortanca ").append(money(band.path("p50"))).append(")\n\n");
        out.append("Tutarlar nominal TL'dir; bugünün fiyatlarıyla hedef yaklaşık ")
                .append(money(r.path("assumptions").path("targetInTodaysPrices"))).append(" eder.");
        return out.toString();
    }

    private String spendingText(JsonNode r) {
        StringBuilder out = new StringBuilder("Son 3 ayda toplam **" + money(r.path("last3MonthsTotal"))
                + "** harcadınız (aylık ortalama " + money(r.path("monthlyAverage")) + "). En büyük kalemler:\n\n");
        int n = 0;
        for (JsonNode c : r.path("categories")) {
            if (n++ == 4) {
                break;
            }
            out.append("- ").append(CATEGORY_TR.getOrDefault(c.path("category").asText(), c.path("category").asText()))
                    .append(": ").append(money(c.path("monthlyAverage"))).append(" / ay (%")
                    .append(fmt(c.path("sharePct").asDouble(), 1)).append(")\n");
        }
        return out.toString();
    }

    private String forecastText(JsonNode r) {
        if (r.has("error")) {
            return "Sizin için henüz bir nakit akışı tahmini yok; bir portföy simülasyonu çalıştıktan sonra burada "
                    + "görünecek.";
        }
        JsonNode months = r.path("months");
        JsonNode last = months.get(months.size() - 1);
        return "Şu anki birikiminiz **" + money(r.path("startBalance")) + "**. \"" + r.path("scenario").asText()
                + "\" varsayımlarıyla " + last.path("month").asText() + " sonunda bakiyeniz büyük olasılıkla **"
                + money(last.path("balance").path("p10")) + " ile " + money(last.path("balance").path("p90"))
                + "** arasında olacak (en olası değer " + money(last.path("balance").path("p50")) + ").";
    }

    private String twinText(JsonNode r) {
        return "**Finansal özetiniz**\n\n- Vadesiz hesap: " + money(r.path("checkingBalance")) + "\n- Vadeli hesap: "
                + money(r.path("depositBalance")) + "\n- Kredi kartı borcu: " + money(r.path("cardDebt"))
                + " (limitin %" + fmt(r.path("cardUtilizationPct").asDouble(), 1) + "'i)\n- Tahmini aylık gelir: "
                + money(r.path("estimatedMonthlyIncome")) + ", ortalama aylık gider: "
                + money(r.path("averageMonthlyExpense")) + "\n- Likidite tamponu: "
                + fmt(r.path("liquidityBufferMonths").asDouble(), 1) + " aylık gider\n";
    }

    // ------------------------------------------------------------------ helpers

    private record Step(String tool, String arguments, String text) {
    }

    private Step tool(String name, Map<String, ?> arguments) {
        try {
            return new Step(name, json.writeValueAsString(arguments), null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Step text(String text) {
        return new Step(null, null, "_Demo modu: yanıt önceden hazırlanmış, araç sonuçları gerçek._\n\n" + text);
    }

    /** The current turn: question, previous answer and the tool results received in this turn. */
    private static final class Turn {
        boolean coach;
        String question = "";
        String previousAnswer = "";
        final Map<String, JsonNode> results = new LinkedHashMap<>();

        static Turn of(Prompt prompt) {
            Turn t = new Turn();
            List<Message> messages = prompt.getInstructions();
            t.coach = prompt.getSystemMessage() != null && prompt.getSystemMessage().getText().contains("finans koçu");
            int lastUser = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i) instanceof UserMessage) {
                    lastUser = i;
                }
            }
            if (lastUser >= 0) {
                t.question = messages.get(lastUser).getText().toLowerCase(TR);
                for (int i = lastUser - 1; i >= 0; i--) {
                    if (messages.get(i) instanceof AssistantMessage a && a.getText() != null) {
                        t.previousAnswer = a.getText();
                        break;
                    }
                }
                ObjectMapper mapper = new ObjectMapper();
                for (int i = lastUser + 1; i < messages.size(); i++) {
                    if (messages.get(i) instanceof ToolResponseMessage tool) {
                        for (ToolResponseMessage.ToolResponse r : tool.getResponses()) {
                            try {
                                t.results.put(r.name(), mapper.readTree(r.responseData()));
                            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                t.results.put(r.name(), mapper.getNodeFactory().textNode(r.responseData()));
                            }
                        }
                    }
                }
            }
            return t;
        }

        JsonNode result(String tool) {
            return results.get(tool);
        }
    }

    private static boolean matches(String text, String regex) {
        return Pattern.compile(regex, FLAGS).matcher(text).find();
    }

    private static Long number(String text, String regex) {
        Matcher m = Pattern.compile(regex, FLAGS).matcher(text);
        if (!m.find()) {
            return null;
        }
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return Long.parseLong(m.group(i));
            }
        }
        return null;
    }

    private static List<Long> numbers(String text, String regex) {
        List<Long> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex, FLAGS).matcher(text);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (v < 100_000 && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static Double decimal(String text, String regex) {
        Matcher m = Pattern.compile(regex, FLAGS).matcher(text);
        return m.find() ? Double.parseDouble(m.group(1).replace(".", "").replace(',', '.')) : null;
    }

    /** "500 bin", "1,5 milyon", "300.000 TL" in the question. */
    private static Double amount(String text) {
        Matcher m = Pattern.compile("(\\d+(?:[.,]\\d+)*)\\s*(milyon|bin)?\\s*(?:tl|lira|₺)?", FLAGS).matcher(text);
        Double best = null;
        while (m.find()) {
            String raw = m.group(1);
            double v = raw.matches("\\d{1,3}(\\.\\d{3})+") ? Double.parseDouble(raw.replace(".", ""))
                    : Double.parseDouble(raw.replace(".", "").replace(',', '.'));
            if ("milyon".equalsIgnoreCase(m.group(2))) {
                v *= 1_000_000;
            } else if ("bin".equalsIgnoreCase(m.group(2))) {
                v *= 1_000;
            }
            if (v >= 1_000 && (best == null || v > best)) {
                best = v;
            }
        }
        return best;
    }

    private static String preset(String q) {
        if (matches(q, "kur şoku")) {
            return "FX_SHOCK";
        }
        if (matches(q, "faiz şoku")) {
            return "RATE_SHOCK";
        }
        if (matches(q, "stagflasyon")) {
            return "STAGFLATION";
        }
        if (matches(q, "iyileşme")) {
            return "RECOVERY";
        }
        if (matches(q, "baz senaryo")) {
            return "BASE";
        }
        return null;
    }

    private static String fmt(double value, int decimals) {
        DecimalFormat f = new DecimalFormat(decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals),
                DecimalFormatSymbols.getInstance(TR));
        return f.format(value);
    }

    private static String pct(JsonNode fraction) {
        return "%" + fmt(fraction.asDouble() * 100, 2);
    }

    private static String points(JsonNode fraction) {
        double v = fraction.asDouble() * 100;
        return (v >= 0 ? "+" : "−") + fmt(Math.abs(v), 2) + " puan";
    }

    private static String money(JsonNode value) {
        return fmt(value.asDouble(), 0) + " TL";
    }
}
