package com.fintwin.assistant.core;

import com.fintwin.assistant.user.CurrentUser;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-side state of one chat turn, handed to the tools through Spring AI's {@link ToolContext}. The tool context
 * is not part of any tool schema, so the model can neither see nor change it: tools read the caller's identity from
 * here only.
 */
public final class TurnContext {

    public static final String KEY = "fintwin.turn";

    private final CurrentUser user;
    private final String conversationId;
    private final int turn;
    private final String userMessage;
    private final List<ToolCallRecord> calls = Collections.synchronizedList(new ArrayList<>());
    private final Set<Long> runIds = Collections.synchronizedSet(new LinkedHashSet<>());

    public TurnContext(CurrentUser user, String conversationId, int turn, String userMessage) {
        this.user = user;
        this.conversationId = conversationId;
        this.turn = turn;
        this.userMessage = userMessage;
    }

    public static TurnContext of(ToolContext context) {
        if (context == null || !(context.getContext().get(KEY) instanceof TurnContext turn)) {
            throw new IllegalStateException("Tool called without a turn context");
        }
        return turn;
    }

    public CurrentUser user() {
        return user;
    }

    public String conversationId() {
        return conversationId;
    }

    public int turn() {
        return turn;
    }

    public String userMessage() {
        return userMessage;
    }

    public List<ToolCallRecord> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    void record(ToolCallRecord call) {
        calls.add(call);
    }

    public void addRunId(long runId) {
        runIds.add(runId);
    }

    public List<Long> runIds() {
        synchronized (runIds) {
            return List.copyOf(runIds);
        }
    }
}
