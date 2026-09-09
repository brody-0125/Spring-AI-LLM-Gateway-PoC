-- Legacy deployment metadata used zero as an unknown-price sentinel.
-- Versioned rate-card prices are authoritative and are deliberately NOT rewritten.
ALTER TABLE llm_gateway_deployment
    ALTER COLUMN input_cost_per_1k_usd DROP NOT NULL,
    ALTER COLUMN output_cost_per_1k_usd DROP NOT NULL,
    ALTER COLUMN cache_read_input_cost_per_1k_usd DROP NOT NULL,
    ALTER COLUMN cache_write_input_cost_per_1k_usd DROP NOT NULL,
    ALTER COLUMN cache_read_input_cost_per_1k_usd DROP DEFAULT,
    ALTER COLUMN cache_write_input_cost_per_1k_usd DROP DEFAULT,
    ALTER COLUMN input_cost_per_1k_usd TYPE NUMERIC(27, 15),
    ALTER COLUMN output_cost_per_1k_usd TYPE NUMERIC(27, 15),
    ALTER COLUMN cache_read_input_cost_per_1k_usd TYPE NUMERIC(27, 15),
    ALTER COLUMN cache_write_input_cost_per_1k_usd TYPE NUMERIC(27, 15);

UPDATE llm_gateway_deployment SET
    input_cost_per_1k_usd = NULLIF(input_cost_per_1k_usd, 0),
    output_cost_per_1k_usd = NULLIF(output_cost_per_1k_usd, 0),
    cache_read_input_cost_per_1k_usd = NULLIF(cache_read_input_cost_per_1k_usd, 0),
    cache_write_input_cost_per_1k_usd = NULLIF(cache_write_input_cost_per_1k_usd, 0);

ALTER TABLE llm_gateway_deployment ADD CONSTRAINT ck_deployment_configured_prices_nonnegative CHECK (
    (input_cost_per_1k_usd IS NULL OR input_cost_per_1k_usd >= 0) AND
    (output_cost_per_1k_usd IS NULL OR output_cost_per_1k_usd >= 0) AND
    (cache_read_input_cost_per_1k_usd IS NULL OR cache_read_input_cost_per_1k_usd >= 0) AND
    (cache_write_input_cost_per_1k_usd IS NULL OR cache_write_input_cost_per_1k_usd >= 0)
);
