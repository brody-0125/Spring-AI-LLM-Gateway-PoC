-- Historical attempt deadlines cannot reconstruct the original request completion deadline.
ALTER TABLE llm_gateway_attempt_journal ADD COLUMN request_deadline TIMESTAMPTZ;
ALTER TABLE llm_gateway_attempt_journal ADD CONSTRAINT journal_request_deadline
    CHECK (request_deadline IS NULL OR request_deadline >= deadline);
