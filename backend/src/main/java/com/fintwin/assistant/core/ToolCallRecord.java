package com.fintwin.assistant.core;

/** One tool call of a chat turn, as audited and returned to the client. */
public record ToolCallRecord(String name, String arguments, String result, boolean ok, long durationMs) {
}
