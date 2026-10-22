package com.fintwin.assistant.core;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one chat turn: system prompt + conversation window + user message, then a tool loop under our control.
 * Spring AI's internal tool execution is switched off, so every round trip passes through here: tool calls are
 * counted against the per-request limit, recorded (via {@link RecordingToolCallback}) and executed by Spring AI's
 * {@link ToolCallingManager} with the server-side {@link TurnContext} as tool context. The same loop serves the
 * blocking and the streaming endpoints and works identically with a scripted test model.
 */
public class AssistantEngine {

    public enum Status { OK, TOOL_LIMIT }

    /** Callbacks for streaming clients. */
    public interface Listener {
        Listener NONE = new Listener() {
        };

        /** The model asked for these tools; text streamed since the last call was interim. */
        default void toolCalls(List<String> names) {
        }

        default void token(String text) {
        }
    }

    public record Outcome(String answer, Status status, Integer promptTokens, Integer completionTokens,
                          Integer totalTokens, String model) {
    }

    static final String TOOL_LIMIT_ANSWER = "Bu soru için izin verilen araç çağrısı sınırına ulaştım ve cevabı "
            + "tamamlayamadım. Soruyu daha dar bir kapsamla (örneğin tek bir koşu veya senaryo için) tekrar sorabilir "
            + "misiniz?";
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    private final ChatClient chatClient;
    private final ToolCallingManager toolCallingManager;
    private final int maxToolCalls;

    public AssistantEngine(ChatModel chatModel, ToolCallingManager toolCallingManager, int maxToolCalls) {
        this.chatClient = ChatClient.create(chatModel);
        this.toolCallingManager = toolCallingManager;
        this.maxToolCalls = maxToolCalls;
    }

    public Outcome run(String systemPrompt, List<ToolCallback> tools, List<Message> history, TurnContext turn,
                       boolean stream, Listener listener) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(history);
        messages.add(new UserMessage(turn.userMessage()));
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools.stream().<ToolCallback>map(RecordingToolCallback::new).toList())
                .internalToolExecutionEnabled(false)
                .toolContext(Map.of(TurnContext.KEY, turn))
                .build();
        Prompt prompt = new Prompt(messages, options);
        Tokens tokens = new Tokens();
        int calls = 0;
        while (true) {
            ChatResponse response = stream ? streamed(prompt, listener) : chatClient.prompt(prompt).call().chatResponse();
            tokens.add(response);
            if (response == null || response.getResult() == null) {
                return tokens.outcome("", Status.OK);
            }
            AssistantMessage output = response.getResult().getOutput();
            if (!output.hasToolCalls()) {
                return tokens.outcome(output.getText() == null ? "" : output.getText().strip(), Status.OK);
            }
            calls += output.getToolCalls().size();
            if (calls > maxToolCalls) {
                return tokens.outcome(TOOL_LIMIT_ANSWER, Status.TOOL_LIMIT);
            }
            listener.toolCalls(output.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList());
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            prompt = new Prompt(result.conversationHistory(), options);
        }
    }

    /** Streams one model call: text chunks go to the listener, tool calls and usage are aggregated. */
    private ChatResponse streamed(Prompt prompt, Listener listener) {
        StringBuilder text = new StringBuilder();
        Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();
        Usage[] usage = new Usage[1];
        String[] model = new String[1];
        chatClient.prompt(prompt).stream().chatResponse().doOnNext(chunk -> {
            if (chunk.getMetadata() != null) {
                Usage u = chunk.getMetadata().getUsage();
                if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) {
                    usage[0] = u;
                }
                if (chunk.getMetadata().getModel() != null && !chunk.getMetadata().getModel().isBlank()) {
                    model[0] = chunk.getMetadata().getModel();
                }
            }
            for (Generation generation : chunk.getResults()) {
                AssistantMessage out = generation.getOutput();
                if (out.getText() != null && !out.getText().isEmpty()) {
                    text.append(out.getText());
                    listener.token(out.getText());
                }
                for (AssistantMessage.ToolCall call : out.getToolCalls()) {
                    toolCalls.put(call.id() == null || call.id().isBlank() ? call.name() + toolCalls.size()
                            : call.id(), call);
                }
            }
        }).blockLast(STREAM_TIMEOUT);
        AssistantMessage message = AssistantMessage.builder().content(text.toString())
                .toolCalls(new ArrayList<>(toolCalls.values())).build();
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (usage[0] != null) {
            metadata.usage(usage[0]);
        }
        if (model[0] != null) {
            metadata.model(model[0]);
        }
        return new ChatResponse(List.of(new Generation(message)), metadata.build());
    }

    /** Token usage summed over the round trips of a turn. */
    private static final class Tokens {
        private Integer prompt;
        private Integer completion;
        private Integer total;
        private String model;

        void add(ChatResponse response) {
            if (response == null || response.getMetadata() == null) {
                return;
            }
            if (response.getMetadata().getModel() != null && !response.getMetadata().getModel().isBlank()) {
                model = response.getMetadata().getModel();
            }
            Usage u = response.getMetadata().getUsage();
            if (u == null) {
                return;
            }
            prompt = sum(prompt, u.getPromptTokens());
            completion = sum(completion, u.getCompletionTokens());
            total = sum(total, u.getTotalTokens());
        }

        private static Integer sum(Integer a, Integer b) {
            if (b == null || b == 0) {
                return a;
            }
            return a == null ? b : a + b;
        }

        Outcome outcome(String answer, Status status) {
            return new Outcome(answer, status, prompt, completion, total, model);
        }
    }
}
