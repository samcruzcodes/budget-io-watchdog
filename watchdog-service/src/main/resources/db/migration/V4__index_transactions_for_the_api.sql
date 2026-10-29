-- The API lists the newest transactions, overall and per user, and reads a user's recent months.
CREATE INDEX transactions_time_idx ON transactions (occurred_at);
CREATE INDEX transactions_user_time_idx ON transactions (user_id, occurred_at);
