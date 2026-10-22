package com.fintwin.assistant;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

/**
 * A chat model that plays a script instead of calling an LLM: each call takes the next step, which may answer with
 * text or ask for tool calls. Every prompt is kept, so tests can check what the model was shown (system prompt,
 * history, tool definitions, tool results).
 */
public class ScriptedChatModel implements ChatModel {

    private final Deque<Function<Prompt, ChatResponse>> script = new ConcurrentLinkedDeque<>();
    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());

    public void reset() {
        script.clear();
        prompts.clear();
    }

    public ScriptedChatModel then(Function<Prompt, ChatResponse> step) {
        script.addLast(step);
        return this;
    }

    public ScriptedChatModel thenToolCall(String name, String arguments) {
        return then(p -> toolCalls(new AssistantMessage.ToolCall("call-" + script.size() + "-" + name, "function",
                name, arguments)));
    }

    public ScriptedChatModel thenText(String text) {
        return then(p -> text(text));
    }

    public List<Prompt> prompts() {
        synchronized (prompts) {
            return List.copyOf(prompts);
        }
    }

    public int remainingSteps() {
        return script.size();
    }

    public static ChatResponse text(String text) {
        return response(new AssistantMessage(text));
    }

    public static ChatResponse toolCalls(AssistantMessage.ToolCall... calls) {
        return response(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build());
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder()
                .model("scripted-test-model").usage(new DefaultUsage(100, 20)).build());
    }

    /** The result text of the most recent tool call with this name in the prompt (null if none). */
    public static String lastToolResult(Prompt prompt, String toolName) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage tool) {
                for (ToolResponseMessage.ToolResponse r : tool.getResponses()) {
                    if (r.name().equals(toolName)) {
                        return r.responseData();
                    }
                }
            }
        }
        return null;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Function<Prompt, ChatResponse> step = script.pollFirst();
        return step == null ? text("(script exhausted)") : step.apply(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatResponse full = call(prompt);
        AssistantMessage out = full.getResult().getOutput();
        if (out.hasToolCalls()) {
            return Flux.just(full);
        }
        String[] words = out.getText().split("(?<= )");
        List<ChatResponse> chunks = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            ChatResponseMetadata metadata = i == words.length - 1 ? full.getMetadata() : new ChatResponseMetadata();
            chunks.add(new ChatResponse(List.of(new Generation(new AssistantMessage(words[i]))), metadata));
        }
        return Flux.fromIterable(chunks);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
    }
}
