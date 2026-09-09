-- Old cost_status alone cannot prove whether the provider reported an amount.
-- Leave historical source NULL rather than inventing provenance or rewriting amounts.
ALTER TABLE llm_gateway_attempt_usage ADD COLUMN cost_source VARCHAR(32)
    CHECK (cost_source IN ('RATE_CARD', 'PROVIDER_REPORTED', 'UNKNOWN'));
