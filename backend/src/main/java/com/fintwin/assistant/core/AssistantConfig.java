package com.fintwin.assistant.core;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Assistant infrastructure: chat memory window, guards and the executor of streaming requests. */
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
class AssistantConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Conversation memory in PostgreSQL ({@code spring_ai_chat_memory}), last N messages per conversation. */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, AssistantProperties properties) {
        return MessageWindowChatMemory.builder().chatMemoryRepository(repository)
                .maxMessages(properties.memoryMessages()).build();
    }

    @Bean
    ConfirmationStore confirmationStore(Clock clock) {
        return new ConfirmationStore(clock);
    }

    @Bean
    RateLimiter assistantRateLimiter(AssistantProperties properties, Clock clock) {
        return new RateLimiter(properties.rateLimit().requests(), properties.rateLimit().window(), clock);
    }

    @Bean
    InputGuard inputGuard() {
        return new InputGuard();
    }

    @Bean
    NumberValidator numberValidator() {
        return new NumberValidator();
    }

    /** Streaming chats run here (each holds a model connection for up to a few minutes). */
    @Bean(destroyMethod = "shutdown")
    ExecutorService assistantStreamExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "assistant-stream");
            t.setDaemon(true);
            return t;
        });
    }
}
