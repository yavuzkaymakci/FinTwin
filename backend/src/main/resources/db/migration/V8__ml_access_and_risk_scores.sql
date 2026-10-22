-- V8: database access for the ML service and the twin's risk scores.
-- Passwords come from Flyway placeholders (ML_DB_PASSWORD / ML_LABELS_DB_PASSWORD, local defaults in
-- application.yml); never commit real credentials.

-- fintwin_ml: read-only access for model training and feature pipelines. It can read the twin (features) and the
-- core banking tables it needs (event labels, profiles, raw transaction texts) but has NO privilege on
-- ground_truth: the generator's hidden variables must never reach PD/churn training.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fintwin_ml') THEN
        CREATE ROLE fintwin_ml LOGIN PASSWORD '${ml_password}';
    END IF;
    -- fintwin_ml_labels: read-only access to ground_truth, used only by the label export (the equivalent of a
    -- hand-labelled training set for the categorisation model) and by offline evaluation scripts.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fintwin_ml_labels') THEN
        CREATE ROLE fintwin_ml_labels LOGIN PASSWORD '${ml_labels_password}';
    END IF;
END
$$;

ALTER ROLE fintwin_ml SET default_transaction_read_only = on;
ALTER ROLE fintwin_ml_labels SET default_transaction_read_only = on;

GRANT USAGE ON SCHEMA twin TO fintwin_ml;
GRANT SELECT ON ALL TABLES IN SCHEMA twin TO fintwin_ml;
ALTER DEFAULT PRIVILEGES IN SCHEMA twin GRANT SELECT ON TABLES TO fintwin_ml;
GRANT SELECT ON customer, customer_event, account, bank_transaction, merchant, merchant_category TO fintwin_ml;
REVOKE ALL ON SCHEMA ground_truth FROM fintwin_ml;
REVOKE ALL ON ALL TABLES IN SCHEMA ground_truth FROM fintwin_ml;

GRANT USAGE ON SCHEMA ground_truth TO fintwin_ml_labels;
GRANT SELECT ON ALL TABLES IN SCHEMA ground_truth TO fintwin_ml_labels;
GRANT SELECT ON bank_transaction, account TO fintwin_ml_labels;

-- Risk scores produced by the ML service for each twin; one row per scoring run and customer (audit trail).
CREATE TABLE twin.customer_risk_score (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id         BIGINT           NOT NULL,
    scored_at           TIMESTAMPTZ      NOT NULL,
    -- Evaluation instant of the twin features the score is based on.
    features_as_of      TIMESTAMPTZ      NOT NULL,
    pd                  DOUBLE PRECISION NOT NULL CHECK (pd BETWEEN 0 AND 1),
    churn               DOUBLE PRECISION NOT NULL CHECK (churn BETWEEN 0 AND 1),
    risk_grade          INTEGER          NOT NULL CHECK (risk_grade BETWEEN 1 AND 10),
    -- Top reasons (code, feature, value, portfolio mean, readable texts) for the PD and churn scores.
    reasons             JSONB            NOT NULL,
    pd_model_version    VARCHAR(64)      NOT NULL,
    churn_model_version VARCHAR(64)      NOT NULL
);

CREATE INDEX idx_customer_risk_score_latest ON twin.customer_risk_score (customer_id, scored_at DESC);
