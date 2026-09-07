package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.AttemptAccountingPort
import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.AttemptOutcome
import com.example.llmgateway.domain.model.Cost
import com.example.llmgateway.domain.model.Usage
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

class PostgresAttemptAccountingAdapter(
    private val jdbcTemplate: JdbcTemplate,
    transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
    private val meterRegistry: MeterRegistry? = null,
) : AttemptAccountingPort {

    private val transaction = transactionTemplate

    override fun record(context: AttemptContext, outcome: AttemptOutcome) {
        val usage = when (outcome) {
            is AttemptOutcome.Success -> outcome.usage
            is AttemptOutcome.Failure -> outcome.usage
            is AttemptOutcome.CancelledWithUsage -> outcome.usage
            AttemptOutcome.Cancelled -> Usage(available = false)
        }
        val cost = when (outcome) {
            is AttemptOutcome.Success -> outcome.cost
            is AttemptOutcome.Failure -> outcome.cost
            is AttemptOutcome.CancelledWithUsage -> outcome.cost
            AttemptOutcome.Cancelled -> Cost()
        }
        try {
            transaction.executeWithoutResult {
                jdbcTemplate.update(
                    """
                    INSERT INTO llm_gateway_attempt_usage (
                        request_id, attempt_id, trace_id, attempt_sequence, caller, tenant,
                        vendor, deployment_id, model_group, provider_model, streaming,
                        started_at, completed_at, outcome, failure_class, usage_available,
                        input_tokens, output_tokens, cache_read_input_tokens,
                        cache_write_input_tokens, reasoning_output_tokens,
                        input_cost_usd, output_cost_usd, cache_read_cost_usd,
                        cache_write_cost_usd, total_cost_usd, cost_status,
                        pricing_version, cost_warnings
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (request_id, attempt_id) DO NOTHING
                    """.trimIndent(),
                    context.requestId.value,
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
                )
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
        is AttemptOutcome.Success -> "success"
        is AttemptOutcome.Failure -> "failure"
        is AttemptOutcome.CancelledWithUsage -> "cancelled"
        AttemptOutcome.Cancelled -> "cancelled"
    }

    private fun failureClass(outcome: AttemptOutcome): String? =
        (outcome as? AttemptOutcome.Failure)?.failureClass?.name
}
