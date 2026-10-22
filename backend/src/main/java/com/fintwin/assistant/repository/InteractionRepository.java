package com.fintwin.assistant.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Audit log of assistant requests ({@code assistant.ai_interaction}). */
@Repository
public class InteractionRepository {

    /** One audited request; JSON columns are passed as serialized JSON. */
    public record Interaction(String conversationId, String assistant, String userId, String userRole,
                              Long customerId, String question, String answer, String status, String toolCallsJson,
                              long durationMs, Integer promptTokens, Integer completionTokens, Integer totalTokens,
                              String validationJson, String model) {
    }

    private final JdbcTemplate jdbc;

    public InteractionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long save(Interaction i) {
        Long id = jdbc.queryForObject("""
                INSERT INTO assistant.ai_interaction (conversation_id, assistant, user_id, user_role, customer_id,
                    question, answer, status, tool_calls, duration_ms, prompt_tokens, completion_tokens, total_tokens,
                    validation, model)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id""", Long.class,
                i.conversationId(), i.assistant(), i.userId(), i.userRole(), i.customerId(), i.question(), i.answer(),
                i.status(), i.toolCallsJson(), i.durationMs(), i.promptTokens(), i.completionTokens(),
                i.totalTokens(), i.validationJson(), i.model());
        return id == null ? 0 : id;
    }
}
