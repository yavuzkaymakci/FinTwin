package com.fintwin.assistant.dto;

/**
 * The assistants' chat provider.
 *
 * @param provider   none, anthropic, ollama or ai-demo
 * @param demo       true for the scripted ai-demo model (answers are prepared, tools are real)
 * @param configured false when no chat model is active (assistant endpoints answer 503)
 */
public record AiProviderInfo(String provider, String model, boolean demo, boolean configured) {
}
