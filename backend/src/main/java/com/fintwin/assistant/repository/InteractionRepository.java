package com.fintwin.assistant.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.assistant.dto.ConversationTurn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final ObjectMapper json;

    public InteractionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** "runId":N inside the tool results, which are stored as escaped JSON strings. */
    private static final Pattern RUN_ID = Pattern.compile("runId\\\\?\": *([0-9]+)");

    /** Turns of a conversation in order, with tool calls and validation as JSON and the run ids they mention. */
    public List<ConversationTurn> history(String conversationId) {
        return jdbc.query("""
                SELECT created_at, question, answer, status, tool_calls::text, validation::text, duration_ms,
                       total_tokens
                FROM assistant.ai_interaction WHERE conversation_id = ? ORDER BY id""", (rs, i) -> {
            String calls = rs.getString(5);
            Set<Long> runIds = new LinkedHashSet<>();
            Matcher m = RUN_ID.matcher(calls == null ? "" : calls);
            while (m.find()) {
                runIds.add(Long.parseLong(m.group(1)));
            }
            return new ConversationTurn(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3),
                    rs.getString(4), read(calls), List.copyOf(runIds), read(rs.getString(6)),
                    rs.getObject(7, Long.class), rs.getObject(8, Integer.class));
        }, conversationId);
    }

    private JsonNode read(String value) {
        try {
            return value == null ? null : json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
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
