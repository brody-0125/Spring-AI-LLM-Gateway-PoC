-- New executions have a durable root; historical executions are not reconstructed.
CREATE TABLE llm_gateway_execution_journal (
    execution_id VARCHAR(128) PRIMARY KEY,
    correlation_id VARCHAR(128) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    caller VARCHAR(128) NOT NULL,
    model_group VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL CHECK (operation = 'CHAT_COMPLETION'),
    streaming BOOLEAN NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    deadline TIMESTAMPTZ NOT NULL CHECK (deadline >= started_at),
    project_id VARCHAR(128),
    service_id VARCHAR(128),
    trace_id VARCHAR(128),
    state VARCHAR(16) NOT NULL CHECK (state IN ('OPEN', 'RECORDED')),
    outcome VARCHAR(16) CHECK (outcome IN ('SUCCESS', 'FAILURE', 'CANCELLED')),
    error_type VARCHAR(128),
    error_code VARCHAR(128),
    recorded_at TIMESTAMPTZ,
    CHECK ((project_id IS NULL) = (service_id IS NULL)),
    CHECK ((state = 'OPEN' AND outcome IS NULL AND recorded_at IS NULL
                AND error_type IS NULL AND error_code IS NULL)
        OR (state = 'RECORDED' AND outcome IS NOT NULL AND recorded_at IS NOT NULL)),
    CHECK (outcome IS DISTINCT FROM 'SUCCESS' OR (error_type IS NULL AND error_code IS NULL))
);
CREATE INDEX idx_execution_journal_pending ON llm_gateway_execution_journal (deadline, execution_id)
    WHERE state = 'OPEN';
CREATE INDEX idx_execution_journal_scope ON llm_gateway_execution_journal (tenant, caller, correlation_id, started_at);

-- Enforced for new writes, without inventing roots for pre-V14 pending attempts.
ALTER TABLE llm_gateway_attempt_journal ADD CONSTRAINT attempt_execution_root
    FOREIGN KEY (execution_id) REFERENCES llm_gateway_execution_journal(execution_id) NOT VALID;

-- The same durable delivery queue can carry either an attempt or an execution receipt.
ALTER TABLE llm_gateway_accounting_outbox ALTER COLUMN attempt_id DROP NOT NULL;
ALTER TABLE llm_gateway_accounting_outbox ADD COLUMN execution_id VARCHAR(128)
    UNIQUE REFERENCES llm_gateway_execution_journal(execution_id);
ALTER TABLE llm_gateway_accounting_outbox ADD CONSTRAINT accounting_event_subject
    CHECK ((attempt_id IS NOT NULL AND execution_id IS NULL)
        OR (attempt_id IS NULL AND execution_id IS NOT NULL));
