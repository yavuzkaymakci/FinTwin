package com.fintwin.assistant.dto;

import java.time.Instant;

/** A conversation in the history list; {@code title} is its first question. */
public record ConversationSummary(String id, String title, int turns, Instant createdAt, Instant lastMessageAt) {
}
