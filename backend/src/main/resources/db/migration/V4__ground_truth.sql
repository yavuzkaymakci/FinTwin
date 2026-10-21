-- V4: ground truth written by the synthetic data generator.
-- Kept in a separate schema so that it is never mistaken for operational data: models must be trained on the
-- public schema only and use these tables solely as labels / for validation.

CREATE SCHEMA ground_truth;

-- Static latent traits drawn once per customer.
CREATE TABLE ground_truth.customer_latent (
    customer_id            BIGINT PRIMARY KEY REFERENCES customer (id),
    -- Financial resilience (literacy, family support); standard normal, higher = less stress.
    resilience             DOUBLE PRECISION NOT NULL,
    -- Idiosyncratic attrition propensity added to the churn hazard logit.
    churn_propensity       DOUBLE PRECISION NOT NULL,
    -- Card repayment style: TRANSACTOR (pays in full), REVOLVER (partial), MIN_PAYER.
    payer_type             VARCHAR(16)      NOT NULL,
    -- Multiplier on discretionary spending (lognormal around 1).
    spending_style         DOUBLE PRECISION NOT NULL,
    -- Baseline coefficient of variation of monthly income.
    income_volatility_base DOUBLE PRECISION NOT NULL,
    -- Share of past inflation passed through in salary raises.
    raise_pass_through     DOUBLE PRECISION NOT NULL,
    salary_day             INTEGER,
    base_monthly_income    NUMERIC(19, 2)   NOT NULL,
    has_car                BOOLEAN          NOT NULL,
    is_renter              BOOLEAN          NOT NULL
);

-- Month-end hidden state; one row per customer and active month.
CREATE TABLE ground_truth.customer_monthly_state (
    customer_id             BIGINT           NOT NULL REFERENCES customer (id),
    period                  DATE             NOT NULL,
    financial_stress        DOUBLE PRECISION NOT NULL,
    debt_to_income          DOUBLE PRECISION NOT NULL,
    real_income_loss        DOUBLE PRECISION NOT NULL,
    card_utilization        DOUBLE PRECISION NOT NULL,
    income_volatility       DOUBLE PRECISION NOT NULL,
    liquidity_buffer_months DOUBLE PRECISION NOT NULL,
    monthly_income          NUMERIC(19, 2)   NOT NULL,
    -- Probability of a behavioural missed payment next month, given the stress.
    p_miss_payment          DOUBLE PRECISION NOT NULL,
    -- Monthly churn hazard (0 when the customer is not eligible to churn).
    p_churn                 DOUBLE PRECISION NOT NULL,
    days_past_due           INTEGER          NOT NULL,
    unemployed              BOOLEAN          NOT NULL,
    churn_intent            BOOLEAN          NOT NULL,
    PRIMARY KEY (customer_id, period)
);

CREATE INDEX idx_gt_monthly_state_period ON ground_truth.customer_monthly_state (period);

-- Per-customer outcome over the generated horizon.
CREATE TABLE ground_truth.customer_outcome (
    customer_id         BIGINT PRIMARY KEY REFERENCES customer (id),
    defaulted           BOOLEAN          NOT NULL,
    default_date        DATE,
    churned             BOOLEAN          NOT NULL,
    churn_date          DATE,
    late_payment_count  INTEGER          NOT NULL,
    unemployment_months INTEGER          NOT NULL,
    max_stress          DOUBLE PRECISION NOT NULL,
    mean_stress         DOUBLE PRECISION NOT NULL
);

-- True spending category of each transaction (target for the description categorisation model).
CREATE TABLE ground_truth.transaction_label (
    transaction_id BIGINT PRIMARY KEY REFERENCES bank_transaction (id),
    category_code  VARCHAR(32) NOT NULL REFERENCES merchant_category (code)
);

-- Parameters of the run that produced the current data set, for reproducibility.
CREATE TABLE ground_truth.generation_run (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seed           BIGINT      NOT NULL,
    customer_count INTEGER     NOT NULL,
    months         INTEGER     NOT NULL,
    end_month      DATE        NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    duration_ms    BIGINT      NOT NULL,
    row_counts     TEXT        NOT NULL
);
