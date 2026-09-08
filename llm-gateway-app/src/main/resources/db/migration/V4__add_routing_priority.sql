ALTER TABLE llm_gateway_deployment
    ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 0;

ALTER TABLE llm_gateway_deployment
    ADD CONSTRAINT llm_gateway_deployment_priority_non_negative
    CHECK (priority >= 0);
