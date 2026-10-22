package com.fintwin.assistant.dto;

import java.util.List;

/**
 * The assistant's answer with what it did.
 *
 * @param status     OK, REFUSED (guard) or TOOL_LIMIT
 * @param toolCalls  tools called in this turn, in order (results omitted)
 * @param runIds     simulation runs the answer refers to
 * @param validation number check: every percentage / amount should have a counterpart in the tool results
 */
public record ChatAnswer(String conversationId, String answer, String status, List<ToolCall> toolCalls,
                         List<Long> runIds, Validation validation, Usage usage, long durationMs, String model) {

    public record ToolCall(String name, String arguments, boolean ok, long durationMs) {
    }

    public record Validation(boolean passed, int checked, List<String> unverified) {
    }

    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }
}
