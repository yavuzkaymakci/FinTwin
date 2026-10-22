package com.fintwin.assistant.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.assistant.core.NumberValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The scripted demo model recognises the example questions, calls real tools and cites only tool numbers. */
class DemoChatModelTests {

    private static final String SCENARIO = "Sen FinTwin'in senaryo asistanısın.";
    private static final String COACH = "Sen FinTwin'in kişisel finans koçusun.";
    private final ObjectMapper json = new ObjectMapper();
    private final DemoChatModel model = new DemoChatModel(json);

    private static Prompt prompt(String system, String previousAnswer, String question, String tool, String result) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        if (previousAnswer != null) {
            messages.add(new UserMessage("önceki soru"));
            messages.add(new AssistantMessage(previousAnswer));
        }
        messages.add(new UserMessage(question));
        if (tool != null) {
            messages.add(AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("1", "function", tool, "{}"))).build());
            messages.add(ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse("1", tool, result))).build());
        }
        return new Prompt(messages);
    }

    private AssistantMessage.ToolCall toolCall(Prompt prompt) {
        AssistantMessage out = model.call(prompt).getResult().getOutput();
        assertThat(out.hasToolCalls()).isTrue();
        return out.getToolCalls().getFirst();
    }

    @Test
    void translatesAMacroQuestionIntoATypedScenario() throws Exception {
        var call = toolCall(prompt(SCENARIO, null,
                "Dolar %40 artarsa ve enflasyon 6 ay boyunca aylık %4 olursa ne olur?", null, null));
        assertThat(call.name()).isEqualTo("createScenario");
        JsonNode p = json.readTree(call.arguments()).path("parameters");
        assertThat(p.path("usdTryChangePct").asDouble()).isEqualTo(40.0);
        assertThat(p.path("monthlyInflationPct")).hasSize(7);
        assertThat(p.path("monthlyInflationPct").get(5).asDouble()).isEqualTo(4.0);
        assertThat(p.path("monthlyInflationPct").get(6).asDouble()).isEqualTo(2.0);

        var start = toolCall(prompt(SCENARIO, null, "Dolar %40 artarsa", "createScenario",
                "{\"scenarioId\": 12, \"code\": \"DEMO_X\"}"));
        assertThat(start.name()).isEqualTo("startSimulation");
        assertThat(start.arguments()).contains("12");
    }

    @Test
    void asksForConfirmationAndStartsOnlyAfterAYes() {
        String confirm = """
                {"status": "CONFIRMATION_REQUIRED", "confirmationToken": "t1", "baseline": "BASE preset",
                 "scenario": {"scenarioId": 12, "code": "DEMO_X", "name": "Dolar +%40",
                   "parameters": {"usdTryChangePct": 40, "policyRateChangeBp": 0, "monthlyInflationPct": [4, 2],
                                  "wageIndexationPct": 90, "wageAdjustmentLagMonths": 6, "horizonMonths": 12,
                                  "monteCarloPaths": 100, "shockProfile": "IMMEDIATE"}}}""";
        Prompt asking = prompt(SCENARIO, null, "kur şoku senaryosunu çalıştır", "startSimulation", confirm);
        String text = model.call(asking).getResult().getOutput().getText();
        assertThat(text).contains("Demo modu").contains("Senaryo ID: 12").contains("onaylıyor musunuz");
        assertThat(new NumberValidator().validate(text, List.of(confirm), "").passed()).isTrue();

        var start = toolCall(prompt(SCENARIO, text, "Evet, onaylıyorum", null, null));
        assertThat(start.name()).isEqualTo("startSimulation");
        assertThat(start.arguments()).isEqualTo("{\"scenarioId\":12}");
        AssistantMessage no = model.call(prompt(SCENARIO, text, "Hayır, vazgeçtim", null, null)).getResult()
                .getOutput();
        assertThat(no.hasToolCalls()).isFalse();
    }

    @Test
    void resultsAreWrittenFromTheToolNumbers() {
        String result = """
                {"runId": 2, "scenarioName": "Kur şoku", "horizonMonths": 12,
                 "portfolio": {"creditCustomers": 3140, "defaultRate": {"mean": 0.0421, "p5": 0.0357, "p95": 0.0494},
                               "expectedLoss": 8010634.0, "lossP99": 10338652.0},
                 "deltaVsBaseline": {"defaultRate": {"mean": 0.0098}, "loss": {"mean": 2297172.0}}}""";
        String text = model.call(prompt(SCENARIO, null, "Koşu 2 sonuçlarını özetle", "getRunResults", result))
                .getResult().getOutput().getText();
        assertThat(text).contains("%4,21").contains("8.010.634 TL").contains("+0,98 puan");
        assertThat(new NumberValidator().validate(text, List.of(result), "Koşu 2").passed()).isTrue();
    }

    @Test
    void coachGoalQuestionsBecomeGoalSimulations() throws Exception {
        var call = toolCall(prompt(COACH, null, "3 yıl içinde 500 bin TL peşinat biriktirmek istiyorum, restoran "
                + "harcamamı yarıya indirirsem ne değişir?", null, null));
        assertThat(call.name()).isEqualTo("simulateSavingsGoal");
        JsonNode args = json.readTree(call.arguments());
        assertThat(args.path("targetAmount").asDouble()).isEqualTo(500_000);
        assertThat(args.path("months").asInt()).isEqualTo(36);
        assertThat(args.path("categoryCuts").get(0).path("category").asText()).isEqualTo("RESTAURANT");
        assertThat(args.path("categoryCuts").get(0).path("cutPct").asDouble()).isEqualTo(50);
        assertThat(args.has("customerId")).isFalse();

        AssistantMessage advice = model.call(prompt(COACH, null, "Hangi hisseyi almalıyım?", null, null))
                .getResult().getOutput();
        assertThat(advice.hasToolCalls()).isFalse();
        assertThat(advice.getText()).contains("Yatırım tavsiyesi veremiyorum");
    }

    @Test
    void theDemoProfileRefusesToRunWithARealProvider() {
        assertThatThrownBy(() -> new AiDemoConfig().demoChatModel(json, "anthropic"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be combined");
        assertThat(new AiDemoConfig().demoChatModel(json, "none")).isNotNull();
    }
}
