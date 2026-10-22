package com.fintwin.assistant.core;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Wraps a tool so every call (arguments, result, duration, failure) is recorded on the turn for the audit log. */
final class RecordingToolCallback implements ToolCallback {

    private static final int MAX_RESULT_CHARS = 20_000;

    private final ToolCallback delegate;

    RecordingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        TurnContext turn = TurnContext.of(toolContext);
        long started = System.nanoTime();
        String name = getToolDefinition().name();
        try {
            String result = delegate.call(toolInput, toolContext);
            turn.record(new ToolCallRecord(name, toolInput, truncate(result), true, elapsed(started)));
            return result;
        } catch (RuntimeException e) {
            turn.record(new ToolCallRecord(name, toolInput, e.getMessage(), false, elapsed(started)));
            throw e;
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_RESULT_CHARS ? value : value.substring(0, MAX_RESULT_CHARS);
    }
}
