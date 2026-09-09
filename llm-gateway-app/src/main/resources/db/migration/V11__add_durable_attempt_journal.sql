-- Additive checkpoint. Historical usage rows cannot prove dispatch intent.
CREATE TABLE llm_gateway_attempt_journal (
    attempt_id VARCHAR(128) PRIMARY KEY,
    execution_id VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    caller VARCHAR(128) NOT NULL,
    attempt_sequence INTEGER NOT NULL CHECK (attempt_sequence > 0),
    attempt_kind VARCHAR(16) NOT NULL CHECK (attempt_kind IN ('INITIAL', 'RETRY', 'FALLBACK')),
    deployment_id VARCHAR(128) NOT NULL,
    model_group VARCHAR(128) NOT NULL,
    streaming BOOLEAN NOT NULL,
    pricing_version VARCHAR(128),
    started_at TIMESTAMPTZ NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    state VARCHAR(32) NOT NULL CHECK (state IN ('PREPARED', 'DISPATCH_INTENT', 'RECORDED', 'ABANDONED')),
    permit_owner VARCHAR(128),
    permit_generation VARCHAR(128),
    dispatch_intent_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ,
    remote_status VARCHAR(16) NOT NULL CHECK (remote_status IN ('NOT_SENT', 'UNKNOWN', 'COMPLETED')),
    receipt_sha256 VARCHAR(64),
    UNIQUE (execution_id, attempt_sequence),
    CHECK (deadline >= started_at),
    CHECK (
        (state IN ('PREPARED', 'ABANDONED') AND permit_owner IS NULL AND permit_generation IS NULL
            AND dispatch_intent_at IS NULL AND remote_status = 'NOT_SENT')
        OR (state IN ('DISPATCH_INTENT', 'RECORDED') AND permit_owner IS NOT NULL AND permit_owner = attempt_id
            AND permit_generation IS NOT NULL AND permit_generation <> '' AND dispatch_intent_at IS NOT NULL
            AND remote_status IN ('UNKNOWN', 'COMPLETED'))
    ),
    CHECK (state <> 'DISPATCH_INTENT' OR remote_status = 'UNKNOWN'),
    CHECK ((state = 'RECORDED' AND recorded_at IS NOT NULL AND receipt_sha256 IS NOT NULL)
        OR (state <> 'RECORDED' AND recorded_at IS NULL AND receipt_sha256 IS NULL))
);

CREATE INDEX idx_journal_pending ON llm_gateway_attempt_journal (deadline, attempt_id)
    WHERE state IN ('PREPARED', 'DISPATCH_INTENT');
CREATE INDEX idx_journal_scope ON llm_gateway_attempt_journal (tenant, caller, execution_id);

CREATE TABLE llm_gateway_accounting_outbox (
    event_id VARCHAR(160) PRIMARY KEY,
    attempt_id VARCHAR(128) NOT NULL UNIQUE REFERENCES llm_gateway_attempt_journal(attempt_id),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    lease_owner VARCHAR(128),
    lease_until TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);
CREATE INDEX idx_accounting_outbox_pending ON llm_gateway_accounting_outbox (available_at, event_id)
    WHERE delivered_at IS NULL;
