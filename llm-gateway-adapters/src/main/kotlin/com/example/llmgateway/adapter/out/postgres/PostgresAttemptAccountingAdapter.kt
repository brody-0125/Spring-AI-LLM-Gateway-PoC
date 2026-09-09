package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.AttemptAccountingPort
import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.execution.AttemptCancelled
import com.example.llmgateway.domain.execution.AttemptCancelledWithUsage
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptFailure
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

class PostgresAttemptAccountingAdapter(
    private val jdbcTemplate: JdbcTemplate,
    transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
    private val meterRegistry: MeterRegistry? = null,
) : AttemptAccountingPort {

    private val transaction = transactionTemplate

    override fun record(context: AttemptContext, outcome: AttemptOutcome) {
        val usage = when (outcome) {
            is AttemptSuccess -> outcome.usage
            is AttemptFailure -> outcome.usage
            is AttemptCancelledWithUsage -> outcome.usage
            AttemptCancelled -> Usage(available = false)
        }
        val cost = when (outcome) {
            is AttemptSuccess -> outcome.cost
            is AttemptFailure -> outcome.cost
            is AttemptCancelledWithUsage -> outcome.cost
            AttemptCancelled -> Cost()
        }
        try {
            transaction.executeWithoutResult {
                val written = jdbcTemplate.update(
                    """
                    INSERT INTO llm_gateway_attempt_usage (
                        request_id, attempt_id, trace_id, attempt_sequence, caller, tenant,
                        vendor, deployment_id, model_group, provider_model, streaming,
                        started_at, completed_at, outcome, provider_request_id, failure_class, usage_available,
                        input_tokens, output_tokens, cache_read_input_tokens,
                        cache_write_input_tokens, reasoning_output_tokens,
                        input_cost_usd, output_cost_usd, cache_read_cost_usd,
                        cache_write_cost_usd, total_cost_usd, cost_status,
                        pricing_version, cost_warnings, execution_id, correlation_id, attempt_kind, cost_source,
                        component_schema_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (request_id, attempt_id) DO NOTHING
                    """.trimIndent(),
                    context.executionId.value,
                    context.attemptId.value,
                    context.traceId,
                    context.sequence,
                    context.caller,
                    context.tenant,
                    context.deployment.vendor.name,
                    context.deployment.id.value,
                    context.deployment.modelGroup.value,
                    context.deployment.model,
                    context.streaming,
                    Timestamp.from(context.startedAt),
                    Timestamp.from(Instant.now(clock)),
                    outcomeName(outcome),
                    providerRequestId(outcome),
                    failureClass(outcome),
                    usage.available,
                    usage.inputTokens,
                    usage.outputTokens,
                    usage.cacheReadInputTokens,
                    usage.cacheWriteInputTokens,
                    usage.reasoningOutputTokens,
                    cost.inputUsd,
                    cost.outputUsd,
                    cost.cacheReadUsd,
                    cost.cacheWriteUsd,
                    cost.usd,
                    cost.status.name,
                    cost.pricingVersion,
                    cost.warnings.joinToString(",") { it.name }.ifBlank { null },
                    context.executionId.value,
                    context.requestId.value,
                    context.kind.name,
                    cost.source.name,
                )
                if (written == 1) {
                    jdbcTemplate.batchUpdate(
                        """INSERT INTO llm_gateway_usage_component
                           (request_id, attempt_id, usage_type, variant, unit, quantity, measurement_source)
                           VALUES (?, ?, ?, ?, ?, ?, ?)""",
                        usage.components, 128,
                        { statement, component ->
                            statement.setString(1, context.executionId.value)
                            statement.setString(2, context.attemptId.value)
                            statement.setString(3, component.key.type.name)
                            statement.setString(4, component.key.variant)
                            statement.setString(5, component.unit.name)
                            statement.setObject(6, component.quantity, java.sql.Types.BIGINT)
                            statement.setString(7, component.source.name)
                        },
                    )
                    jdbcTemplate.batchUpdate(
                        """INSERT INTO llm_gateway_cost_line
                           (request_id, attempt_id, usage_type, variant, unit, quantity,
                            unit_price_usd, amount_usd, cost_status, cost_source)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        cost.lines, 128,
                        { statement, line ->
                            statement.setString(1, context.executionId.value)
                            statement.setString(2, context.attemptId.value)
                            statement.setString(3, line.key.type.name)
                            statement.setString(4, line.key.variant)
                            statement.setString(5, line.unit.name)
                            statement.setObject(6, line.quantity, java.sql.Types.BIGINT)
                            statement.setBigDecimal(7, line.unitPriceUsd)
                            statement.setBigDecimal(8, line.amount)
                            statement.setString(9, line.status.name)
                            statement.setString(10, line.source.name)
                        },
                    )
                }
            }
        } catch (error: Exception) {
            meterRegistry?.let {
                Counter.builder("llm.gateway.accounting.write.failure")
                    .description("Durable provider attempt accounting writes that failed")
                    .register(it)
                    .increment()
            }
            throw error
        }
    }

    private fun outcomeName(outcome: AttemptOutcome): String = when (outcome) {
        is AttemptSuccess -> "success"
        is AttemptFailure -> "failure"
        is AttemptCancelledWithUsage -> "cancelled"
        AttemptCancelled -> "cancelled"
    }

    private fun failureClass(outcome: AttemptOutcome): String? =
        (outcome as? AttemptFailure)?.failureClass?.name

    private fun providerRequestId(outcome: AttemptOutcome): String? = when (outcome) {
        is AttemptSuccess -> outcome.providerRequestId
        is AttemptFailure -> outcome.providerRequestId
        is AttemptCancelledWithUsage -> outcome.providerRequestId
        AttemptCancelled -> null
    }
}
