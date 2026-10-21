-- V6: core banking feed (transactional-outbox style replay of the core banking tables to Kafka).

CREATE SCHEMA feed;

-- One row per event to publish, in global event-time order (position). customer_seq is the gap-free per-customer
-- sequence across all topics. Built by the feed's prepare step from the core banking tables.
CREATE TABLE feed.outbox (
    position     BIGINT PRIMARY KEY,
    kind         VARCHAR(32) NOT NULL,
    source_id    BIGINT      NOT NULL,
    customer_id  BIGINT      NOT NULL,
    customer_seq BIGINT      NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_outbox_customer_seq UNIQUE (customer_id, customer_seq)
);

-- Single-row replay bookkeeping; survives restarts so a replay can be resumed.
CREATE TABLE feed.replay_state (
    id                 SMALLINT PRIMARY KEY CHECK (id = 1),
    dataset_epoch      VARCHAR(64),
    prepared_at        TIMESTAMPTZ,
    feed_start         TIMESTAMPTZ,
    feed_end           TIMESTAMPTZ,
    total_events       BIGINT NOT NULL DEFAULT 0,
    cursor_position    BIGINT NOT NULL DEFAULT 0,
    cursor_occurred_at TIMESTAMPTZ,
    published_total    BIGINT NOT NULL DEFAULT 0
);

INSERT INTO feed.replay_state (id) VALUES (1);
