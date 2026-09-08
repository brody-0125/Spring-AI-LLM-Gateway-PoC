ALTER TABLE llm_gateway_attempt_usage
    ADD COLUMN IF NOT EXISTS provider_request_id VARCHAR(256);
