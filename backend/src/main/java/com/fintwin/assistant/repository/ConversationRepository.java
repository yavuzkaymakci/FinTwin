package com.fintwin.assistant.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
