package com.fintwin.assistant.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * One question and answer of a conversation, from the audit log.
 *
 * @param toolCalls [{name, arguments, result (truncated), ok, durationMs}]
 * @param runIds    simulation runs mentioned in the tool results
 */
public record ConversationTurn(Instant createdAt, String question, String answer, String status, JsonNode toolCalls,
                               List<Long> runIds, JsonNode validation, Long durationMs, Integer totalTokens) {
}
