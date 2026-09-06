CREATE TABLE IF NOT EXISTS llm_gateway_deployment (
    id VARCHAR(128) PRIMARY KEY,
    vendor VARCHAR(64) NOT NULL,
    dialect VARCHAR(64) NOT NULL,
    model_group VARCHAR(128) NOT NULL,
    model VARCHAR(256) NOT NULL,
    enabled BOOLEAN NOT NULL,
    weight INTEGER NOT NULL CHECK (weight >= 0),
    supports_streaming BOOLEAN NOT NULL,
    input_cost_per_1k_usd NUMERIC(18, 8) NOT NULL,
    output_cost_per_1k_usd NUMERIC(18, 8) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS llm_gateway_routing_version (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    version BIGINT NOT NULL CHECK (version >= 1)
);

INSERT INTO llm_gateway_routing_version (id, version)
VALUES (1, 1)
ON CONFLICT (id) DO NOTHING;
