-- Every transaction the watchdog has accepted, stored exactly once: transaction_id is the idempotency key, so a
-- message Kafka delivers twice can't create a second row. Never edit this file once committed; add V2__... instead.
CREATE TABLE transactions (
    transaction_id UUID        PRIMARY KEY,
    source         TEXT        NOT NULL,
    user_id        TEXT        NOT NULL,
    card_id        TEXT        NOT NULL,
    merchant       TEXT        NOT NULL,
    category       TEXT        NOT NULL,
    amount_cents   BIGINT      NOT NULL CHECK (amount_cents >= 0),
    currency       TEXT        NOT NULL,
    country        TEXT        NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL, -- event time (the simulator's clock for simulated data)
    published_at   TIMESTAMPTZ NOT NULL, -- real time the source published it
    processed_at   TIMESTAMPTZ NOT NULL  -- real time the watchdog stored it; latency = processed_at - published_at
);

-- Rebuilding each card's history in time order at startup (Phase 2).
CREATE INDEX transactions_card_time_idx ON transactions (card_id, occurred_at);

-- Throughput and latency look at recently processed rows (Phase 3).
CREATE INDEX transactions_processed_idx ON transactions (processed_at);
