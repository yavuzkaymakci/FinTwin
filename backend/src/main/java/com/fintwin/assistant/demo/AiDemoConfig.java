package com.fintwin.assistant.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Profile {@code ai-demo}: the scripted {@link DemoChatModel} as the assistants' chat model, for demos without an
 * LLM. It refuses to start together with a real provider ({@code spring.ai.model.chat} other than none), so it can
 * never shadow Anthropic or Ollama.
 */
@Configuration
@Profile("ai-demo")
class AiDemoConfig {

    @Bean
    DemoChatModel demoChatModel(ObjectMapper json, @Value("${spring.ai.model.chat:none}") String provider) {
        if (!"none".equals(provider)) {
            throw new IllegalStateException("The ai-demo profile cannot be combined with the '" + provider
                    + "' chat provider; start either ai-demo or a real provider profile");
        }
        return new DemoChatModel(json);
    }
}
