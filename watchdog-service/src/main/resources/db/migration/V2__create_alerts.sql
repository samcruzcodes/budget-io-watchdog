-- Every alert a detector raised. One per transaction and detector, so a redelivered message can't add a second one.
-- Written in the same database transaction as its transaction row, so the foreign key always holds.
CREATE TABLE alerts (
    alert_id       UUID        PRIMARY KEY, -- derived from (transaction_id, detector), so a republish keeps it
    transaction_id UUID        NOT NULL REFERENCES transactions (transaction_id),
    user_id        TEXT        NOT NULL,
    card_id        TEXT        NOT NULL,
    detector       TEXT        NOT NULL,    -- a rule ID today; plain text so a future model's alerts fit too
    issue_type     TEXT        NOT NULL,
    explanation    TEXT        NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,    -- the transaction's event time
    detected_at    TIMESTAMPTZ NOT NULL,    -- real time the alert was raised
    published_at   TIMESTAMPTZ,             -- real time Kafka confirmed it; null until then
    UNIQUE (transaction_id, detector)
);

-- Newest alerts, overall and per user (the API).
CREATE INDEX alerts_time_idx ON alerts (occurred_at);
CREATE INDEX alerts_user_time_idx ON alerts (user_id, occurred_at);
