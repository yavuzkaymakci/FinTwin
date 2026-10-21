-- V5: data the core banking feed needs to publish a self-contained event stream.

-- Balance of an account when it enters the generated history (at the start of the horizon, or when it is opened
-- during it). balance = opening_balance + credits - debits (reversed for credit cards, whose balance is debt).
ALTER TABLE account ADD COLUMN opening_balance NUMERIC(19, 2) NOT NULL DEFAULT 0;

-- Other leg of a transfer between the same customer's accounts (virman, FX exchange, card payment).
-- No FK: both legs are inserted in the same batch and reference each other.
ALTER TABLE bank_transaction ADD COLUMN counterpart_transaction_id BIGINT;

-- Bank-originated postings such as interest.
ALTER TABLE bank_transaction DROP CONSTRAINT bank_transaction_channel_check;
ALTER TABLE bank_transaction ADD CONSTRAINT bank_transaction_channel_check
    CHECK (channel IN ('POS', 'ECOMMERCE', 'ATM', 'EFT_FAST', 'AUTO_PAYMENT', 'SYSTEM'));

INSERT INTO merchant_category (id, code, name) VALUES (21, 'INTEREST', 'Interest charged or earned');
