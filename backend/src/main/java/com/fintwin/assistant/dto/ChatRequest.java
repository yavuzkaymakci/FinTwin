package com.fintwin.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A chat message.
 *
 * @param conversationId id returned by an earlier answer; omit to start a new conversation
 */
public record ChatRequest(
        @Pattern(regexp = "[0-9a-fA-F-]{36}", message = "must be a conversation id returned by the server")
        String conversationId,
        @NotBlank @Size(max = 4000) String message) {
}
