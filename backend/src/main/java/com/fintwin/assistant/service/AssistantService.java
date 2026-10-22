package com.fintwin.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.assistant.core.AssistantDefinition;
import com.fintwin.assistant.core.AssistantEngine;
import com.fintwin.assistant.core.AssistantExceptions;
import com.fintwin.assistant.core.AssistantProperties;
import com.fintwin.assistant.core.AssistantType;
import com.fintwin.assistant.core.InputGuard;
import com.fintwin.assistant.core.NumberValidator;
import com.fintwin.assistant.core.RateLimiter;
import com.fintwin.assistant.core.ToolCallRecord;
import com.fintwin.assistant.core.TurnContext;
import com.fintwin.assistant.dto.AiProviderInfo;
import com.fintwin.assistant.dto.ChatAnswer;
import com.fintwin.assistant.dto.ChatRequest;
import com.fintwin.assistant.dto.ConversationSummary;
import com.fintwin.assistant.dto.ConversationTurn;
import com.fintwin.assistant.repository.ConversationRepository;
import com.fintwin.assistant.repository.InteractionRepository;
import com.fintwin.assistant.user.CurrentUser;
import com.fintwin.assistant.user.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Chat requests of both assistants. The order of checks is fixed: identity ({@link CurrentUserProvider}) → role →
 * conversation ownership → rate limit → input guard → model with tools → number check → memory and audit log.
 * Every request that passes identity and role is audited, including refused and rate-limited ones.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final int AUDIT_RESULT_CHARS = 2_000;

    /** The request-thread part of a turn (identity, conversation), handed to {@link #execute}. */
    public record PreparedTurn(AssistantType type, CurrentUser user, String conversationId, boolean newConversation,
                               String message, long startedNanos) {
    }

    private final CurrentUserProvider users;
    private final Map<AssistantType, AssistantDefinition> definitions = new EnumMap<>(AssistantType.class);
    private final ObjectProvider<ChatModel> chatModels;
    private final ToolCallingManager toolCallingManager;
    private final ChatMemory memory;
    private final ConversationRepository conversations;
    private final InteractionRepository interactions;
    private final RateLimiter rateLimiter;
    private final InputGuard guard;
    private final NumberValidator validator;
    private final AssistantProperties properties;
    private final ObjectMapper json;
    private volatile AssistantEngine engine;

    public AssistantService(CurrentUserProvider users, List<AssistantDefinition> definitions,
                            ObjectProvider<ChatModel> chatModels, ToolCallingManager toolCallingManager,
                            ChatMemory memory, ConversationRepository conversations, InteractionRepository interactions,
                            RateLimiter rateLimiter, InputGuard guard, NumberValidator validator,
                            AssistantProperties properties, ObjectMapper json) {
        this.users = users;
        definitions.forEach(d -> this.definitions.put(d.type(), d));
        this.chatModels = chatModels;
        this.toolCallingManager = toolCallingManager;
        this.memory = memory;
        this.conversations = conversations;
        this.interactions = interactions;
        this.rateLimiter = rateLimiter;
        this.guard = guard;
        this.validator = validator;
        this.properties = properties;
        this.json = json;
    }

    public ChatAnswer chat(AssistantType type, ChatRequest request) {
        return execute(prepare(type, request), false, AssistantEngine.Listener.NONE);
    }

    /** Checks run on the request thread (the identity is bound to it). */
    public PreparedTurn prepare(AssistantType type, ChatRequest request) {
        long started = System.nanoTime();
        CurrentUser user = users.current();
        if (!user.has(type.role())) {
            throw new AssistantExceptions.Forbidden("The " + type + " assistant is for role " + type.role());
        }
        String conversationId = request.conversationId();
        boolean created = conversationId == null;
        if (created) {
            conversationId = UUID.randomUUID().toString();
        } else {
            var conversation = conversations.find(conversationId)
                    .orElseThrow(AssistantExceptions.ConversationNotFound::new);
            if (!conversation.assistant().equals(type.name()) || !conversation.userId().equals(user.userId())
                    || !Objects.equals(conversation.customerId(), user.customerId())) {
                throw new AssistantExceptions.ConversationNotFound();
            }
        }
        PreparedTurn turn = new PreparedTurn(type, user, conversationId, created, request.message().strip(), started);
        if (!rateLimiter.tryAcquire(user.userId() + "/" + type)) {
            audit(turn, null, "RATE_LIMITED", List.of(), null, null);
            throw new AssistantExceptions.RateLimited();
        }
        return turn;
    }

    public ChatAnswer execute(PreparedTurn prepared, boolean stream, AssistantEngine.Listener listener) {
        if (prepared.newConversation()) {
            conversations.create(prepared.conversationId(), prepared.type().name(), prepared.user().userId(),
                    prepared.user().customerId());
        }
        Optional<String> refusal = guard.check(prepared.type(), prepared.message());
        if (refusal.isPresent()) {
            log.warn("Assistant request refused by the input guard (user {}, {})", prepared.user().userId(),
                    prepared.type());
            audit(prepared, refusal.get(), "REFUSED", List.of(), null, null);
            listener.token(refusal.get());
            return answer(prepared, refusal.get(), "REFUSED", List.of(), List.of(), null, null);
        }
        AssistantEngine current = engine();
        int turnNumber = conversations.nextTurn(prepared.conversationId());
        TurnContext turn = new TurnContext(prepared.user(), prepared.conversationId(), turnNumber, prepared.message());
        AssistantDefinition definition = definitions.get(prepared.type());
        AssistantEngine.Outcome outcome;
        try {
            outcome = current.run(definition.systemPrompt(turn), definition.tools(), memory.get(prepared.conversationId()),
                    turn, stream, listener);
        } catch (RuntimeException e) {
            log.error("Assistant {} failed for user {}", prepared.type(), prepared.user().userId(), e);
            audit(prepared, null, "ERROR", turn.calls(), null, null);
            throw new AssistantExceptions.ModelFailed("The language model call failed: " + e.getMessage(), e);
        }
        NumberValidator.Result validation = validator.validate(outcome.answer(),
                turn.calls().stream().filter(ToolCallRecord::ok).map(ToolCallRecord::result).toList(),
                prepared.message());
        if (!validation.passed()) {
            log.warn("Assistant answer has numbers without a tool source (conversation {}): {}",
                    prepared.conversationId(), validation.unverified());
        }
        memory.add(prepared.conversationId(), List.of(new UserMessage(prepared.message()),
                new AssistantMessage(outcome.answer())));
        audit(prepared, outcome.answer(), outcome.status().name(), turn.calls(), validation, outcome);
        return answer(prepared, outcome.answer(), outcome.status().name(), turn.calls(), turn.runIds(), validation,
                outcome);
    }

    /** Which chat provider is active (from the ChatModel bean, not from configuration that might be stale). */
    public AiProviderInfo provider() {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            return new AiProviderInfo("none", null, false, false);
        }
        String name = model.getClass().getSimpleName();
        String provider = name.startsWith("Anthropic") ? "anthropic" : name.startsWith("Ollama") ? "ollama"
                : name.startsWith("Demo") ? "ai-demo" : name;
        String modelName = model.getDefaultOptions() == null ? null : model.getDefaultOptions().getModel();
        return new AiProviderInfo(provider, modelName, "ai-demo".equals(provider), true);
    }

    private AssistantEngine engine() {
        AssistantEngine e = engine;
        if (e == null) {
            ChatModel model = chatModels.getIfAvailable();
            if (model == null) {
                throw new AssistantExceptions.NotConfigured();
            }
            e = new AssistantEngine(model, toolCallingManager, properties.maxToolCalls());
            engine = e;
        }
        return e;
    }

    private ChatAnswer answer(PreparedTurn turn, String text, String status, List<ToolCallRecord> calls,
                              List<Long> runIds, NumberValidator.Result validation, AssistantEngine.Outcome outcome) {
        return new ChatAnswer(turn.conversationId(), text, status,
                calls.stream().map(c -> new ChatAnswer.ToolCall(c.name(), c.arguments(), truncate(c.result()), c.ok(),
                                c.durationMs()))
                        .toList(),
                runIds,
                validation == null ? null : new ChatAnswer.Validation(validation.passed(), validation.checked(),
                        validation.unverified()),
                outcome == null ? null : new ChatAnswer.Usage(outcome.promptTokens(), outcome.completionTokens(),
                        outcome.totalTokens()),
                elapsedMs(turn), outcome == null ? null : outcome.model());
    }

    private void audit(PreparedTurn turn, String answer, String status, List<ToolCallRecord> calls,
                       NumberValidator.Result validation, AssistantEngine.Outcome outcome) {
        try {
            List<Map<String, Object>> toolCalls = calls.stream().map(c -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", c.name());
                row.put("arguments", c.arguments());
                row.put("result", c.result() == null || c.result().length() <= AUDIT_RESULT_CHARS ? c.result()
                        : c.result().substring(0, AUDIT_RESULT_CHARS));
                row.put("ok", c.ok());
                row.put("durationMs", c.durationMs());
                return row;
            }).toList();
            interactions.save(new InteractionRepository.Interaction(turn.conversationId(), turn.type().name(),
                    turn.user().userId(), turn.user().role().name(), turn.user().customerId(), turn.message(), answer,
                    status, json.writeValueAsString(toolCalls), elapsedMs(turn),
                    outcome == null ? null : outcome.promptTokens(), outcome == null ? null : outcome.completionTokens(),
                    outcome == null ? null : outcome.totalTokens(),
                    validation == null ? null : json.writeValueAsString(validation),
                    outcome == null ? null : outcome.model()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 4_000 ? value : value.substring(0, 4_000);
    }

    /** The current user's conversations with an assistant, newest first. */
    public List<ConversationSummary> conversations(AssistantType type) {
        CurrentUser user = users.current();
        if (!user.has(type.role())) {
            throw new AssistantExceptions.Forbidden("The " + type + " assistant is for role " + type.role());
        }
        return conversations.list(type.name(), user.userId(), user.customerId());
    }

    /** All turns of one of the current user's conversations (from the audit log, including tool calls). */
    public List<ConversationTurn> history(AssistantType type, String conversationId) {
        CurrentUser user = users.current();
        var conversation = conversations.find(conversationId)
                .orElseThrow(AssistantExceptions.ConversationNotFound::new);
        if (!user.has(type.role()) || !conversation.assistant().equals(type.name())
                || !conversation.userId().equals(user.userId())
                || !Objects.equals(conversation.customerId(), user.customerId())) {
            throw new AssistantExceptions.ConversationNotFound();
        }
        return interactions.history(conversationId);
    }

    private static long elapsedMs(PreparedTurn turn) {
        return (System.nanoTime() - turn.startedNanos()) / 1_000_000;
    }
}
