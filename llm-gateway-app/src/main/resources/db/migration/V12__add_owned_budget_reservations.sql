-- No default funds or inferred identity mappings. Grant issuance/import is a separate authority.
CREATE TABLE llm_gateway_budget_binding (
    tenant VARCHAR(128) NOT NULL,
    caller VARCHAR(128) NOT NULL,
    project_id VARCHAR(128) NOT NULL,
    service_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant, caller)
);

CREATE TABLE llm_gateway_budget_grant (
    grant_id VARCHAR(128) PRIMARY KEY,
    project_id VARCHAR(128) NOT NULL,
    owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL CHECK (period_end > period_start),
    face_usd NUMERIC(30,18) NOT NULL CHECK (face_usd >= 0),
    held_usd NUMERIC(30,18) NOT NULL DEFAULT 0 CHECK (held_usd >= 0),
    settled_usd NUMERIC(30,18) NOT NULL DEFAULT 0 CHECK (settled_usd >= 0),
    available_usd NUMERIC(30,18) GENERATED ALWAYS AS (face_usd - held_usd - settled_usd) STORED,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'FROZEN')),
    UNIQUE (project_id, period_start, period_end)
);

CREATE TABLE llm_gateway_service_budget (
    grant_id VARCHAR(128) NOT NULL REFERENCES llm_gateway_budget_grant(grant_id),
    service_id VARCHAR(128) NOT NULL,
    limit_usd NUMERIC(30,18) NOT NULL CHECK (limit_usd >= 0),
    held_usd NUMERIC(30,18) NOT NULL DEFAULT 0 CHECK (held_usd >= 0),
    settled_usd NUMERIC(30,18) NOT NULL DEFAULT 0 CHECK (settled_usd >= 0),
    PRIMARY KEY (grant_id, service_id)
);

CREATE TABLE llm_gateway_chat_budget_profile (
    deployment_id VARCHAR(128) PRIMARY KEY,
    provider_model VARCHAR(256) NOT NULL,
    vendor VARCHAR(32) NOT NULL,
    revision VARCHAR(128) NOT NULL,
    max_billable_input_tokens BIGINT NOT NULL CHECK (max_billable_input_tokens > 0),
    max_billable_output_tokens BIGINT NOT NULL CHECK (max_billable_output_tokens > 0),
    enabled BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE llm_gateway_budget_reservation (
    attempt_id VARCHAR(128) PRIMARY KEY REFERENCES llm_gateway_attempt_journal(attempt_id),
    grant_id VARCHAR(128) NOT NULL,
    service_id VARCHAR(128) NOT NULL,
    owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL CHECK (period_end > period_start),
    profile_revision VARCHAR(128) NOT NULL,
    pricing_version VARCHAR(128) NOT NULL,
    input_bound BIGINT NOT NULL CHECK (input_bound > 0),
    output_bound BIGINT NOT NULL CHECK (output_bound > 0),
    input_price_usd NUMERIC(30,18) NOT NULL CHECK (input_price_usd >= 0),
    output_price_usd NUMERIC(30,18) NOT NULL CHECK (output_price_usd >= 0),
    cache_read_price_usd NUMERIC(30,18) NOT NULL CHECK (cache_read_price_usd >= 0),
    cache_write_price_usd NUMERIC(30,18) NOT NULL CHECK (cache_write_price_usd >= 0),
    reserved_usd NUMERIC(30,18) NOT NULL CHECK (reserved_usd >= 0),
    actual_usd NUMERIC(30,18),
    state VARCHAR(24) NOT NULL CHECK (state IN ('HELD', 'REVIEW_REQUIRED', 'SETTLED', 'RELEASED')),
    FOREIGN KEY (grant_id, service_id) REFERENCES llm_gateway_service_budget(grant_id, service_id),
    CHECK ((state = 'SETTLED' AND actual_usd IS NOT NULL AND actual_usd >= 0)
        OR (state <> 'SETTLED' AND actual_usd IS NULL))
);
CREATE INDEX idx_budget_reservation_pending ON llm_gateway_budget_reservation (grant_id, state)
    WHERE state IN ('HELD', 'REVIEW_REQUIRED');
