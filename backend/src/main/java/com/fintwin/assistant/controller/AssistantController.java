package com.fintwin.assistant.controller;

import com.fintwin.assistant.core.AssistantEngine;
import com.fintwin.assistant.core.AssistantExceptions;
import com.fintwin.assistant.core.AssistantType;
import com.fintwin.assistant.dto.ChatAnswer;
import com.fintwin.assistant.dto.ChatRequest;
import com.fintwin.assistant.dto.ConversationSummary;
import com.fintwin.assistant.dto.ConversationTurn;
import com.fintwin.assistant.service.AssistantService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Chat endpoints of the scenario assistant (RISK_ANALYST) and the customer coach (CUSTOMER), blocking and as
 * server-sent events. SSE events: {@code conversation} {conversationId}, {@code tool} {names} (text streamed before
 * it was interim), {@code token} {text}, {@code done} (the full {@link ChatAnswer}) and {@code error}.
 */
@RestController
@RequestMapping("/api/assistants")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);
    private static final long SSE_TIMEOUT_MS = 6 * 60 * 1000L;

    private final AssistantService assistants;
    private final ExecutorService executor;

    public AssistantController(AssistantService assistants, ExecutorService assistantStreamExecutor) {
        this.assistants = assistants;
        this.executor = assistantStreamExecutor;
    }

    @PostMapping("/scenario/chat")
    public ChatAnswer scenario(@Valid @RequestBody ChatRequest request) {
        return assistants.chat(AssistantType.SCENARIO, request);
    }

    @PostMapping("/coach/chat")
    public ChatAnswer coach(@Valid @RequestBody ChatRequest request) {
        return assistants.chat(AssistantType.COACH, request);
    }

    /** The current user's conversations ({@code assistant}: scenario or coach), newest first. */
    @GetMapping("/{assistant}/conversations")
    public List<ConversationSummary> conversations(@PathVariable String assistant) {
        return assistants.conversations(type(assistant));
    }

    /** All turns of one of the current user's conversations, with tool calls, validation and run ids. */
    @GetMapping("/{assistant}/conversations/{conversationId}")
    public List<ConversationTurn> history(@PathVariable String assistant, @PathVariable String conversationId) {
        return assistants.history(type(assistant), conversationId);
    }

    private static AssistantType type(String assistant) {
        return switch (assistant) {
            case "scenario" -> AssistantType.SCENARIO;
            case "coach" -> AssistantType.COACH;
            default -> throw new AssistantExceptions.ConversationNotFound();
        };
    }

    @PostMapping(value = "/scenario/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scenarioStream(@Valid @RequestBody ChatRequest request) {
        return stream(AssistantType.SCENARIO, request);
    }

    @PostMapping(value = "/coach/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter coachStream(@Valid @RequestBody ChatRequest request) {
        return stream(AssistantType.COACH, request);
    }

    private SseEmitter stream(AssistantType type, ChatRequest request) {
        AssistantService.PreparedTurn turn = assistants.prepare(type, request);  // identity on the request thread
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        executor.submit(() -> {
            try {
                send(emitter, "conversation", Map.of("conversationId", turn.conversationId()));
                ChatAnswer answer = assistants.execute(turn, true, new AssistantEngine.Listener() {
                    @Override
                    public void toolCalls(List<String> names) {
                        send(emitter, "tool", Map.of("names", names));
                    }

                    @Override
                    public void token(String text) {
                        send(emitter, "token", Map.of("text", text));
                    }
                });
                send(emitter, "done", answer);
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("Streaming chat failed: {}", e.getMessage());
                send(emitter, "error", Map.of("error", e.getClass().getSimpleName(),
                        "detail", String.valueOf(e.getMessage())));
                emitter.complete();
            }
        });
        return emitter;
    }

    @ExceptionHandler(AssistantExceptions.NotConfigured.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> notConfigured(AssistantExceptions.NotConfigured e) {
        return Map.of("error", "ASSISTANT_NOT_CONFIGURED", "detail", e.getMessage());
    }

    @ExceptionHandler(AssistantExceptions.ModelFailed.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> modelFailed(AssistantExceptions.ModelFailed e) {
        return Map.of("error", "MODEL_CALL_FAILED", "detail", e.getMessage());
    }

    @ExceptionHandler(AssistantExceptions.RateLimited.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, String> rateLimited(AssistantExceptions.RateLimited e) {
        return Map.of("error", "RATE_LIMITED", "detail", e.getMessage());
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("Client disconnected", e);
        }
    }
}
