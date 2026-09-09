-- Expand only: historical correlation-keyed rows have no recoverable execution identity.
-- New writers retain request_id as the physical compatibility key, containing execution_id.
ALTER TABLE llm_gateway_attempt_usage
    ADD COLUMN execution_id VARCHAR(128),
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN attempt_kind VARCHAR(16),
    ADD CONSTRAINT ck_attempt_execution_identity CHECK (
        (execution_id IS NULL AND correlation_id IS NULL AND attempt_kind IS NULL)
        OR (execution_id IS NOT NULL AND correlation_id IS NOT NULL
            AND execution_id = request_id AND attempt_kind IS NOT NULL
            AND attempt_kind IN ('INITIAL', 'RETRY', 'FALLBACK'))
    );

CREATE INDEX idx_attempt_execution ON llm_gateway_attempt_usage (execution_id)
    WHERE execution_id IS NOT NULL;
CREATE UNIQUE INDEX uq_attempt_server_id ON llm_gateway_attempt_usage (attempt_id)
    WHERE execution_id IS NOT NULL;

ALTER TABLE llm_gateway_request_usage
    ADD COLUMN execution_id VARCHAR(128),
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN initial_count INTEGER,
    ADD COLUMN retry_count INTEGER,
    ADD CONSTRAINT ck_request_execution_identity CHECK (
        (execution_id IS NULL AND correlation_id IS NULL AND initial_count IS NULL AND retry_count IS NULL)
        OR (execution_id IS NOT NULL AND correlation_id IS NOT NULL AND execution_id = request_id
            AND initial_count IS NOT NULL AND initial_count >= 0
            AND retry_count IS NOT NULL AND retry_count >= 0
            AND initial_count + retry_count + fallback_count = attempt_count)
    );

CREATE UNIQUE INDEX uq_request_execution ON llm_gateway_request_usage (execution_id)
    WHERE execution_id IS NOT NULL;
CREATE INDEX idx_request_correlation_scope
    ON llm_gateway_request_usage (tenant, caller, correlation_id, started_at)
    WHERE execution_id IS NOT NULL;
