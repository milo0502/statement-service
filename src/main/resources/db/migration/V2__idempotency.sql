ALTER TABLE statements
    ADD CONSTRAINT uk_statement_idempotency
    UNIQUE (customer_id, account_id, period_start, period_end, sha256);