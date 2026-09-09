-- Match the immutable rate-card quantum without narrowing the existing integer range.
-- This preserves old recorded values; it cannot reconstruct previously rounded-away charges.
ALTER TABLE llm_gateway_attempt_usage
    ALTER COLUMN input_cost_usd TYPE NUMERIC(30, 18),
    ALTER COLUMN output_cost_usd TYPE NUMERIC(30, 18),
    ALTER COLUMN cache_read_cost_usd TYPE NUMERIC(30, 18),
    ALTER COLUMN cache_write_cost_usd TYPE NUMERIC(30, 18),
    ALTER COLUMN total_cost_usd TYPE NUMERIC(30, 18);

ALTER TABLE llm_gateway_request_usage
    ALTER COLUMN input_cost_usd TYPE NUMERIC(30, 18),
    ALTER COLUMN output_cost_usd TYPE NUMERIC(30, 18),
    ALTER COLUMN cache_read_cost_usd TYPE NUMERIC(30, 18),
    ALTER COLUMN cache_write_cost_usd TYPE NUMERIC(30, 18),
    ALTER COLUMN total_cost_usd TYPE NUMERIC(30, 18);
