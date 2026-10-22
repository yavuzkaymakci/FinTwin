package com.fintwin.assistant.core;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/** An assistant: its type, system prompt and tools. */
public interface AssistantDefinition {

    AssistantType type();

    String systemPrompt(TurnContext turn);

    List<ToolCallback> tools();
}
