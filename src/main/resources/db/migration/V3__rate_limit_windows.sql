CREATE TABLE rate_limit_windows (
    rate_limit_key VARCHAR(256) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rate_limit_windows_updated_at ON rate_limit_windows(updated_at);
