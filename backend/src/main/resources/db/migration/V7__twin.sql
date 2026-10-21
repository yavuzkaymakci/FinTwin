-- V7: customer digital twin. Fed only by Kafka events; never reads the core banking (public) or ground_truth schemas.

CREATE SCHEMA twin;

-- Immutable log of every event the twin accepted. Also the re-sequencing buffer: events are applied to the
-- projection strictly in customer_seq order, whatever order they arrived in.
CREATE TABLE twin.event_store (
    event_id        UUID PRIMARY KEY,
    customer_id     BIGINT      NOT NULL,
    customer_seq    BIGINT      NOT NULL,
    event_type      VARCHAR(48) NOT NULL,
    schema_version  INTEGER     NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    topic           VARCHAR(96) NOT NULL,
    kafka_partition INTEGER,
    kafka_offset    BIGINT,
    payload         JSONB       NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_event_store_customer_seq UNIQUE (customer_id, customer_seq)
);

CREATE INDEX idx_event_store_customer_time ON twin.event_store (customer_id, occurred_at);

CREATE FUNCTION twin.reject_event_store_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'twin.event_store is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_store_append_only
    BEFORE UPDATE OR DELETE ON twin.event_store
    FOR EACH ROW EXECUTE FUNCTION twin.reject_event_store_change();

-- Current projection per customer (state = full projection; feature columns for querying).
CREATE TABLE twin.customer_twin_state (
    customer_id                  BIGINT PRIMARY KEY,
    version                      BIGINT         NOT NULL,
    last_seq                     BIGINT         NOT NULL,
    last_event_at                TIMESTAMPTZ    NOT NULL,
    state                        JSONB          NOT NULL,
    features                     JSONB          NOT NULL,
    checking_balance             NUMERIC(19, 2) NOT NULL,
    deposit_balance              NUMERIC(19, 2) NOT NULL,
    card_debt                    NUMERIC(19, 2) NOT NULL,
    total_debt                   NUMERIC(19, 2) NOT NULL,
    utilization_rate             DOUBLE PRECISION,
    spend_30d                    NUMERIC(19, 2) NOT NULL,
    spend_90d                    NUMERIC(19, 2) NOT NULL,
    income_estimate              NUMERIC(19, 2),
    monthly_obligations          NUMERIC(19, 2) NOT NULL,
    debt_to_income               DOUBLE PRECISION,
    late_payment_count           INTEGER        NOT NULL,
    days_since_last_late_payment INTEGER,
    liquidity_buffer_months      DOUBLE PRECISION,
    updated_at                   TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Month-start snapshots: state before the first event of the month, features evaluated at month start.
CREATE TABLE twin.customer_twin_snapshot (
    customer_id BIGINT      NOT NULL,
    as_of       DATE        NOT NULL,
    last_seq    BIGINT      NOT NULL,
    state       JSONB       NOT NULL,
    features    JSONB       NOT NULL,
    PRIMARY KEY (customer_id, as_of)
);
