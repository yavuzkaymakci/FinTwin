package com.fintwin.assistant.core;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Errors of the assistant API, mapped to HTTP statuses. */
public final class AssistantExceptions {

    private AssistantExceptions() {
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) {
            super(message);
        }
    }

    /** Unknown conversation, or one that belongs to another user (not distinguished on purpose). */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class ConversationNotFound extends RuntimeException {
        public ConversationNotFound() {
            super("Unknown conversation");
        }
    }

    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public static class RateLimited extends RuntimeException {
        public RateLimited() {
            super("Too many assistant requests; try again later");
        }
    }

    /** No chat model configured (profile "anthropic" or "ollama" not active). */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static class NotConfigured extends RuntimeException {
        public NotConfigured() {
            super("No chat model configured; start the backend with the 'anthropic' or 'ollama' profile");
        }
    }

    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public static class ModelFailed extends RuntimeException {
        public ModelFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
