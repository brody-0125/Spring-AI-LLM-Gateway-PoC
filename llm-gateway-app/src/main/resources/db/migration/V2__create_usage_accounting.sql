ALTER TABLE llm_gateway_deployment
    ADD COLUMN IF NOT EXISTS cache_read_input_cost_per_1k_usd NUMERIC(18, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_write_input_cost_per_1k_usd NUMERIC(18, 8) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS llm_gateway_pricing_version (
    version VARCHAR(128) PRIMARY KEY,
    source VARCHAR(128) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS llm_gateway_deployment_pricing (
    deployment_id VARCHAR(128) NOT NULL,
    pricing_version VARCHAR(128) NOT NULL REFERENCES llm_gateway_pricing_version(version),
    input_cost_per_token_usd NUMERIC(24, 18),
    output_cost_per_token_usd NUMERIC(24, 18),
    cache_read_input_cost_per_token_usd NUMERIC(24, 18),
    cache_write_input_cost_per_token_usd NUMERIC(24, 18),
    PRIMARY KEY (deployment_id, pricing_version)
);

CREATE INDEX IF NOT EXISTS idx_llm_gateway_deployment_pricing_lookup
    ON llm_gateway_deployment_pricing (deployment_id, pricing_version);

CREATE TABLE IF NOT EXISTS llm_gateway_attempt_usage (
    request_id VARCHAR(128) NOT NULL,
    attempt_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128),
    attempt_sequence INTEGER NOT NULL CHECK (attempt_sequence > 0),
    caller VARCHAR(128) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    vendor VARCHAR(64) NOT NULL,
    deployment_id VARCHAR(128) NOT NULL,
    model_group VARCHAR(128) NOT NULL,
    provider_model VARCHAR(256) NOT NULL,
    streaming BOOLEAN NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    failure_class VARCHAR(64),
    usage_available BOOLEAN NOT NULL,
    input_tokens BIGINT NOT NULL CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL CHECK (output_tokens >= 0),
    cache_read_input_tokens BIGINT NOT NULL CHECK (cache_read_input_tokens >= 0),
    cache_write_input_tokens BIGINT NOT NULL CHECK (cache_write_input_tokens >= 0),
    reasoning_output_tokens BIGINT NOT NULL CHECK (reasoning_output_tokens >= 0),
    input_cost_usd NUMERIC(24, 12) NOT NULL CHECK (input_cost_usd >= 0),
    output_cost_usd NUMERIC(24, 12) NOT NULL CHECK (output_cost_usd >= 0),
    cache_read_cost_usd NUMERIC(24, 12) NOT NULL CHECK (cache_read_cost_usd >= 0),
    cache_write_cost_usd NUMERIC(24, 12) NOT NULL CHECK (cache_write_cost_usd >= 0),
    total_cost_usd NUMERIC(24, 12) NOT NULL CHECK (total_cost_usd >= 0),
    cost_status VARCHAR(32) NOT NULL,
    pricing_version VARCHAR(128),
    cost_warnings VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id, attempt_id)
);

CREATE INDEX IF NOT EXISTS idx_llm_gateway_attempt_usage_started_at
    ON llm_gateway_attempt_usage (started_at);

CREATE INDEX IF NOT EXISTS idx_llm_gateway_attempt_usage_tenant_started_at
    ON llm_gateway_attempt_usage (tenant, started_at);

CREATE INDEX IF NOT EXISTS idx_llm_gateway_attempt_usage_model_group_started_at
    ON llm_gateway_attempt_usage (model_group, started_at);

CREATE INDEX IF NOT EXISTS idx_llm_gateway_attempt_usage_trace_id
    ON llm_gateway_attempt_usage (trace_id);
