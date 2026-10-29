-- The simulator's answer key, one row per transaction. Only the evaluation package reads it; detection never does.
-- No foreign key to transactions: labels arrive on their own topic, so a label can be stored before its transaction.
CREATE TABLE ground_truth_labels (
    transaction_id UUID        PRIMARY KEY,
    persona        TEXT        NOT NULL,
    issue_type     TEXT        NOT NULL, -- NONE for normal transactions, including look-alikes
    scenario_id    UUID,                 -- groups one injected issue or look-alike; null for ordinary transactions
    edge_case      TEXT,                 -- the look-alike this belongs to, or null
    received_at    TIMESTAMPTZ NOT NULL  -- real time the watchdog stored it
);

-- Counting scenarios per issue type (recall).
CREATE INDEX ground_truth_labels_issue_idx ON ground_truth_labels (issue_type, scenario_id);
