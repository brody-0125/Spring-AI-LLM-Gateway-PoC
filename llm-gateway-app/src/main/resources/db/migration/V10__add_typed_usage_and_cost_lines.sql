-- Additional unit/variant prices share the exact immutable deployment/version boundary.
-- The four existing unqualified token prices remain in deployment_pricing; no key has two writers.
ALTER TABLE llm_gateway_attempt_usage ADD COLUMN component_schema_version SMALLINT
    CHECK (component_schema_version = 1);

CREATE TABLE llm_gateway_component_pricing (
    deployment_id VARCHAR(128) NOT NULL,
    pricing_version VARCHAR(128) NOT NULL,
    usage_type VARCHAR(48) NOT NULL,
    variant VARCHAR(64) NOT NULL DEFAULT '',
    unit VARCHAR(32) NOT NULL,
    usd_per_unit NUMERIC(24,18) CHECK (usd_per_unit >= 0),
    PRIMARY KEY (deployment_id, pricing_version, usage_type, variant),
    FOREIGN KEY (deployment_id, pricing_version)
        REFERENCES llm_gateway_deployment_pricing(deployment_id, pricing_version),
    CHECK (usage_type <> 'REASONING_OUTPUT_TOKENS'),
    CHECK (variant <> '' OR usage_type NOT IN
        ('INPUT_TOKENS', 'OUTPUT_TOKENS', 'CACHE_READ_INPUT_TOKENS', 'CACHE_WRITE_INPUT_TOKENS')),
    CHECK (variant = '' OR variant ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CHECK (usage_type NOT IN ('INPUT_TOKENS', 'OUTPUT_TOKENS', 'REASONING_OUTPUT_TOKENS') OR variant = ''),
    CHECK (
        (usage_type IN ('INPUT_TOKENS', 'OUTPUT_TOKENS', 'CACHE_READ_INPUT_TOKENS',
            'CACHE_WRITE_INPUT_TOKENS', 'REASONING_OUTPUT_TOKENS') AND unit = 'TOKEN') OR
        (usage_type = 'RERANK_QUERIES' AND unit = 'QUERY') OR
        (usage_type = 'RERANK_DOCUMENT_BLOCKS' AND unit = 'DOCUMENT_BLOCK') OR
        (usage_type = 'TOOL_INVOCATIONS' AND unit = 'INVOCATION')
    )
);

-- Nullable quantity distinguishes unavailable from a reported zero; no legacy provenance is inferred.
CREATE TABLE llm_gateway_usage_component (
    request_id VARCHAR(128) NOT NULL,
    attempt_id VARCHAR(128) NOT NULL,
    usage_type VARCHAR(48) NOT NULL,
    variant VARCHAR(64) NOT NULL DEFAULT '',
    unit VARCHAR(32) NOT NULL,
    quantity BIGINT CHECK (quantity >= 0),
    measurement_source VARCHAR(32) NOT NULL
        CHECK (measurement_source IN ('PROVIDER_REPORTED', 'ESTIMATED', 'UNKNOWN')),
    PRIMARY KEY (request_id, attempt_id, usage_type, variant),
    FOREIGN KEY (request_id, attempt_id)
        REFERENCES llm_gateway_attempt_usage(request_id, attempt_id) ON DELETE CASCADE,
    CHECK ((quantity IS NULL) = (measurement_source = 'UNKNOWN')),
    CHECK (variant = '' OR variant ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CHECK (usage_type NOT IN ('INPUT_TOKENS', 'OUTPUT_TOKENS', 'REASONING_OUTPUT_TOKENS') OR variant = ''),
    CHECK (
        (usage_type IN ('INPUT_TOKENS', 'OUTPUT_TOKENS', 'CACHE_READ_INPUT_TOKENS',
            'CACHE_WRITE_INPUT_TOKENS', 'REASONING_OUTPUT_TOKENS') AND unit = 'TOKEN') OR
        (usage_type = 'RERANK_QUERIES' AND unit = 'QUERY') OR
        (usage_type = 'RERANK_DOCUMENT_BLOCKS' AND unit = 'DOCUMENT_BLOCK') OR
        (usage_type = 'TOOL_INVOCATIONS' AND unit = 'INVOCATION')
    )
);

CREATE TABLE llm_gateway_cost_line (
    request_id VARCHAR(128) NOT NULL,
    attempt_id VARCHAR(128) NOT NULL,
    usage_type VARCHAR(48) NOT NULL,
    variant VARCHAR(64) NOT NULL DEFAULT '',
    unit VARCHAR(32) NOT NULL,
    quantity BIGINT CHECK (quantity >= 0),
    unit_price_usd NUMERIC(24,18) CHECK (unit_price_usd >= 0),
    amount_usd NUMERIC(30,18) CHECK (amount_usd >= 0),
    cost_status VARCHAR(32) NOT NULL CHECK (cost_status IN ('REPORTED', 'ESTIMATED', 'PARTIAL', 'UNKNOWN')),
    cost_source VARCHAR(32) NOT NULL CHECK (cost_source IN ('RATE_CARD', 'PROVIDER_REPORTED', 'UNKNOWN')),
    PRIMARY KEY (request_id, attempt_id, usage_type, variant),
    FOREIGN KEY (request_id, attempt_id)
        REFERENCES llm_gateway_attempt_usage(request_id, attempt_id) ON DELETE CASCADE,
    CHECK ((amount_usd IS NULL) = (cost_status = 'UNKNOWN')),
    CHECK (usage_type <> 'REASONING_OUTPUT_TOKENS'),
    CHECK (variant = '' OR variant ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CHECK (usage_type NOT IN ('INPUT_TOKENS', 'OUTPUT_TOKENS', 'REASONING_OUTPUT_TOKENS') OR variant = ''),
    CHECK (
        (usage_type IN ('INPUT_TOKENS', 'OUTPUT_TOKENS', 'CACHE_READ_INPUT_TOKENS',
            'CACHE_WRITE_INPUT_TOKENS', 'REASONING_OUTPUT_TOKENS') AND unit = 'TOKEN') OR
        (usage_type = 'RERANK_QUERIES' AND unit = 'QUERY') OR
        (usage_type = 'RERANK_DOCUMENT_BLOCKS' AND unit = 'DOCUMENT_BLOCK') OR
        (usage_type = 'TOOL_INVOCATIONS' AND unit = 'INVOCATION')
    )
);
