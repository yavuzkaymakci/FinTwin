-- V10: AI assistants (scenario assistant, customer coach): conversations, audit log and chat memory.

CREATE SCHEMA IF NOT EXISTS assistant;

-- A conversation belongs to one user (and, for the coach, one customer); other users cannot continue it.
CREATE TABLE assistant.conversation (
    id              VARCHAR(36) PRIMARY KEY,
    assistant       VARCHAR(16) NOT NULL CHECK (assistant IN ('SCENARIO', 'COACH')),
    user_id         VARCHAR(64) NOT NULL,
    customer_id     BIGINT,
    turns           INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_conversation_user ON assistant.conversation (user_id, last_message_at DESC);

-- Audit log: one row per chat request, including refused and rate-limited ones.
CREATE TABLE assistant.ai_interaction (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id   VARCHAR(36),
    assistant         VARCHAR(16) NOT NULL,
    user_id           VARCHAR(64) NOT NULL,
    user_role         VARCHAR(32) NOT NULL,
    customer_id       BIGINT,
    question          TEXT        NOT NULL,
    answer            TEXT,
    status            VARCHAR(24) NOT NULL
        CHECK (status IN ('OK', 'REFUSED', 'RATE_LIMITED', 'TOOL_LIMIT', 'ERROR')),
    -- [{name, arguments, result (truncated), ok, durationMs}]
    tool_calls        JSONB       NOT NULL DEFAULT '[]',
    duration_ms       BIGINT      NOT NULL,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    -- Number check of the answer: {passed, checked, unverified: [...]}
    validation        JSONB,
    model             VARCHAR(128),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_interaction_user ON assistant.ai_interaction (user_id, created_at DESC);
CREATE INDEX idx_ai_interaction_conversation ON assistant.ai_interaction (conversation_id, created_at);

-- Spring AI JDBC chat memory (schema-postgresql.sql of spring-ai-model-chat-memory-repository-jdbc 1.1.8);
-- created here because the schema is owned by Flyway (initialize-schema: never).
CREATE TABLE IF NOT EXISTS spring_ai_chat_memory (
    conversation_id VARCHAR(36) NOT NULL,
    content         TEXT        NOT NULL,
    type            VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS spring_ai_chat_memory_conversation_id_timestamp_idx
    ON spring_ai_chat_memory (conversation_id, "timestamp");
