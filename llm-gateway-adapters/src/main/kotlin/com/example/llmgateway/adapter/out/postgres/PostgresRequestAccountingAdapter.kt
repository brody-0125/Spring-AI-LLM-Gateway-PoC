package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.RequestAccountingPort
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

class PostgresRequestAccountingAdapter(
    private val jdbcTemplate: JdbcTemplate,
    transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
    private val meterRegistry: MeterRegistry? = null,
) : RequestAccountingPort {

    private val transaction = transactionTemplate

    override fun record(
        context: RequestContext,
        request: CanonicalChatRequest,
        outcome: RequestOutcome,
    ) {
        try {
            transaction.executeWithoutResult {
                val written = jdbcTemplate.update(
                """
                INSERT INTO llm_gateway_request_usage (
                    request_id, trace_id, caller, tenant, model_group, streaming,
                    started_at, completed_at, outcome, error_type, error_code,
                    attempt_count, fallback_count, usage_available,
                    input_tokens, output_tokens, cache_read_input_tokens,
                    cache_write_input_tokens, reasoning_output_tokens,
                    input_cost_usd, output_cost_usd, cache_read_cost_usd,
                    cache_write_cost_usd, total_cost_usd, cost_status,
                    execution_id, correlation_id, initial_count, retry_count
                )
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                       COUNT(a.attempt_id)::INTEGER,
                       COUNT(*) FILTER (WHERE a.attempt_kind = 'FALLBACK')::INTEGER,
                       COALESCE(BOOL_OR(a.usage_available), FALSE),
                       COALESCE(SUM(a.input_tokens), 0)::BIGINT,
                       COALESCE(SUM(a.output_tokens), 0)::BIGINT,
                       COALESCE(SUM(a.cache_read_input_tokens), 0)::BIGINT,
                       COALESCE(SUM(a.cache_write_input_tokens), 0)::BIGINT,
                       COALESCE(SUM(a.reasoning_output_tokens), 0)::BIGINT,
                       COALESCE(SUM(a.input_cost_usd), 0),
                       COALESCE(SUM(a.output_cost_usd), 0),
                       COALESCE(SUM(a.cache_read_cost_usd), 0),
                       COALESCE(SUM(a.cache_write_cost_usd), 0),
                       COALESCE(SUM(a.total_cost_usd), 0),
                       CASE
                           WHEN COUNT(a.attempt_id) = 0 THEN 'UNKNOWN'
                           WHEN BOOL_OR(a.cost_status = 'UNKNOWN') THEN 'UNKNOWN'
                           WHEN BOOL_OR(a.cost_status = 'PARTIAL') THEN 'PARTIAL'
                           WHEN BOOL_OR(a.cost_status = 'ESTIMATED') THEN 'ESTIMATED'
                           ELSE 'REPORTED'
                       END,
                       ?, ?,
                       COUNT(*) FILTER (WHERE a.attempt_kind = 'INITIAL')::INTEGER,
                       COUNT(*) FILTER (WHERE a.attempt_kind = 'RETRY')::INTEGER
                FROM llm_gateway_attempt_usage a
                WHERE a.execution_id = ? AND a.tenant = ? AND a.caller = ?
                ON CONFLICT (request_id) DO UPDATE SET
                    trace_id = EXCLUDED.trace_id,
                    caller = EXCLUDED.caller,
                    tenant = EXCLUDED.tenant,
                    model_group = EXCLUDED.model_group,
                    streaming = EXCLUDED.streaming,
                    started_at = EXCLUDED.started_at,
                    completed_at = EXCLUDED.completed_at,
                    outcome = EXCLUDED.outcome,
                    error_type = EXCLUDED.error_type,
                    error_code = EXCLUDED.error_code,
                    attempt_count = EXCLUDED.attempt_count,
                    fallback_count = EXCLUDED.fallback_count,
                    usage_available = EXCLUDED.usage_available,
                    input_tokens = EXCLUDED.input_tokens,
                    output_tokens = EXCLUDED.output_tokens,
                    cache_read_input_tokens = EXCLUDED.cache_read_input_tokens,
                    cache_write_input_tokens = EXCLUDED.cache_write_input_tokens,
                    reasoning_output_tokens = EXCLUDED.reasoning_output_tokens,
                    input_cost_usd = EXCLUDED.input_cost_usd,
                    output_cost_usd = EXCLUDED.output_cost_usd,
                    cache_read_cost_usd = EXCLUDED.cache_read_cost_usd,
                    cache_write_cost_usd = EXCLUDED.cache_write_cost_usd,
                    total_cost_usd = EXCLUDED.total_cost_usd,
                    cost_status = EXCLUDED.cost_status,
                    execution_id = EXCLUDED.execution_id,
                    correlation_id = EXCLUDED.correlation_id,
                    initial_count = EXCLUDED.initial_count,
                    retry_count = EXCLUDED.retry_count
                WHERE llm_gateway_request_usage.execution_id = EXCLUDED.execution_id
                  AND llm_gateway_request_usage.tenant = EXCLUDED.tenant
                  AND llm_gateway_request_usage.caller = EXCLUDED.caller
                  AND llm_gateway_request_usage.correlation_id = EXCLUDED.correlation_id
                """.trimIndent(),
                context.executionId.value,
                context.traceId,
                context.caller,
                context.tenant,
                request.modelGroup.value,
                request.stream,
                Timestamp.from(context.startedAt),
                Timestamp.from(Instant.now(clock)),
                outcome.status.name.lowercase(),
                outcome.errorType,
                outcome.errorCode,
                context.executionId.value,
                context.requestId.value,
                context.executionId.value,
                context.tenant,
                context.caller,
                )
                check(written == 1) { "Execution accounting identity cannot be changed" }
            }
        } catch (error: Exception) {
            meterRegistry?.let {
                Counter.builder("llm.gateway.accounting.request.write.failure")
                    .description("Durable request accounting writes that failed")
                    .register(it)
                    .increment()
            }
            throw error
        }
    }
}
