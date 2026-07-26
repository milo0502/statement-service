CREATE TABLE orphaned_s3_objects (
    id UUID PRIMARY KEY,
    bucket VARCHAR(255) NOT NULL,
    object_key TEXT NOT NULL,
    statement_id UUID,
    customer_id VARCHAR(128),
    account_id VARCHAR(128),
    period_start DATE,
    period_end DATE,
    size_bytes BIGINT,
    sha256 VARCHAR(64),
    reason VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL,
    first_detected_at TIMESTAMPTZ NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    last_error TEXT
);

CREATE INDEX idx_orphaned_s3_objects_status_detected
    ON orphaned_s3_objects(status, first_detected_at);

CREATE INDEX idx_orphaned_s3_objects_bucket_key_status
    ON orphaned_s3_objects(bucket, object_key, status);
