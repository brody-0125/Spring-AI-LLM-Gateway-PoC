CREATE TABLE IF NOT EXISTS llm_gateway_request_usage (
    request_id VARCHAR(128) PRIMARY KEY,
    trace_id VARCHAR(128),
    caller VARCHAR(128) NOT NULL,
    tenant VARCHAR(128) NOT NULL,
    model_group VARCHAR(128) NOT NULL,
    streaming BOOLEAN NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    error_type VARCHAR(128),
    error_code VARCHAR(128),
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
    fallback_count INTEGER NOT NULL CHECK (fallback_count >= 0),
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
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_llm_gateway_request_usage_started_at
    ON llm_gateway_request_usage (started_at);

CREATE INDEX IF NOT EXISTS idx_llm_gateway_request_usage_tenant_started_at
    ON llm_gateway_request_usage (tenant, started_at);

CREATE INDEX IF NOT EXISTS idx_llm_gateway_request_usage_model_group_started_at
    ON llm_gateway_request_usage (model_group, started_at);
