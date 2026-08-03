-- Dead-letter/retry bookkeeping for the RECEIVED -> TRIAGING -> RETRYING ->
-- {TRIAGED, FAILED} state machine. A database-backed retry queue: no Kafka
-- or SQS needed for this system's volume — a scheduled poll of
-- "RETRYING rows whose next_retry_at has passed" is enough.
ALTER TABLE incidents ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE incidents ADD COLUMN failure_stage VARCHAR(64);
ALTER TABLE incidents ADD COLUMN error_category VARCHAR(64);
ALTER TABLE incidents ADD COLUMN last_error TEXT;
ALTER TABLE incidents ADD COLUMN next_retry_at TIMESTAMPTZ;

-- The retry scheduler's poll query filters on (status, next_retry_at).
CREATE INDEX idx_incidents_retry_due ON incidents (status, next_retry_at);
