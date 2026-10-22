package com.fintwin.assistant.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fintwin.assistant.dto.ConversationSummary;

import java.util.List;
import java.util.Optional;

/** Conversations and their owners ({@code assistant.conversation}). */
@Repository
public class ConversationRepository {

    public record Conversation(String id, String assistant, String userId, Long customerId, int turns) {
    }

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Conversation> find(String id) {
        return jdbc.query("""
                SELECT id, assistant, user_id, customer_id, turns FROM assistant.conversation WHERE id = ?""",
                (rs, i) -> new Conversation(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, Long.class), rs.getInt(5)), id).stream().findFirst();
    }

    /** The user's conversations with an assistant, newest first; the title is the first question. */
    public List<ConversationSummary> list(String assistant, String userId, Long customerId) {
        return jdbc.query("""
                SELECT c.id, (SELECT i.question FROM assistant.ai_interaction i WHERE i.conversation_id = c.id
                              ORDER BY i.id LIMIT 1), c.turns, c.created_at, c.last_message_at
                FROM assistant.conversation c
                WHERE c.assistant = ? AND c.user_id = ? AND c.customer_id IS NOT DISTINCT FROM ?
                ORDER BY c.last_message_at DESC LIMIT 50""",
                (rs, i) -> new ConversationSummary(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()),
                assistant, userId, customerId);
    }

    public void create(String id, String assistant, String userId, Long customerId) {
        jdbc.update("INSERT INTO assistant.conversation (id, assistant, user_id, customer_id) VALUES (?, ?, ?, ?)",
                id, assistant, userId, customerId);
    }

    /** Increments the turn counter and returns the new turn number (1 for the first message). */
    public int nextTurn(String id) {
        Integer turn = jdbc.queryForObject("""
                UPDATE assistant.conversation SET turns = turns + 1, last_message_at = now() WHERE id = ?
                RETURNING turns""", Integer.class, id);
        return turn == null ? 1 : turn;
    }
}
