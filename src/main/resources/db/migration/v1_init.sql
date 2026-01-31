CREATE TABLE statements (
                            id UUID PRIMARY KEY,
                            customer_id VARCHAR(128) NOT NULL,
                            account_id VARCHAR(128) NOT NULL,
                            period_start DATE NOT NULL,
                            period_end DATE NOT NULL,
                            object_key TEXT NOT NULL,
                            content_type VARCHAR(128) NOT NULL,
                            size_bytes BIGINT NOT NULL,
                            sha256 VARCHAR(64) NOT NULL,
                            uploaded_at TIMESTAMPTZ NOT NULL,
                            status VARCHAR(32) NOT NULL
);

CREATE INDEX idx_statements_customer_id ON statements(customer_id);
CREATE INDEX idx_statements_customer_account_period ON statements(customer_id, account_id, period_start, period_end);

CREATE TABLE audit_events (
                              id UUID PRIMARY KEY,
                              customer_id VARCHAR(128),
                              action VARCHAR(64) NOT NULL,
                              statement_id UUID,
                              ip VARCHAR(64),
                              user_agent TEXT,
                              created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_events_created_at ON audit_events(created_at);
