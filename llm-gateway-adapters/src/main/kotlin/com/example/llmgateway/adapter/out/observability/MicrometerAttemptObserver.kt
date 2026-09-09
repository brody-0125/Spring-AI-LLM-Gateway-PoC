package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.domain.accounting.CostStatus
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.accounting.UsageType
import com.example.llmgateway.domain.execution.AttemptCancelled
import com.example.llmgateway.domain.execution.AttemptCancelledWithUsage
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptFailure
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import com.example.llmgateway.domain.observation.AttemptObservationHandle
import com.example.llmgateway.domain.observation.NoOpAttemptObservationHandle
import com.example.llmgateway.domain.observation.ObservationHandle
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

class MicrometerAttemptObserver(
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
) : AttemptObserverPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    override fun start(context: AttemptContext): AttemptObservationHandle {
        val observation = Observation.createNotStarted("llm.gateway.attempt", observationRegistry)
            .lowCardinalityKeyValue("vendor", context.deployment.vendor.name.lowercase())
            .lowCardinalityKeyValue("model_group", context.deployment.modelGroup.value)
            .lowCardinalityKeyValue("stream", context.streaming.toString())
        return try {
            observation.start()
            var firstToken: Long? = null
            val handle = StartedObservation<AttemptOutcome>(
                observation, observationRegistry, { logObserverFailure(context, it) },
            ) { outcome, startedNanos ->
                observation.lowCardinalityKeyValue("outcome", outcomeName(outcome))
                recordStop(context, outcome, startedNanos, firstToken)
            }
            object : AttemptObservationHandle, ObservationHandle<AttemptOutcome> by handle {
                override fun firstToken() = handle.ifActive {
                    if (firstToken == null) firstToken = System.nanoTime()
                }
            }
        } catch (error: Exception) {
            runCatching { observation.stop() }
            logObserverFailure(context, error)
            NoOpAttemptObservationHandle
        }
    }

    private fun recordStop(
        context: AttemptContext,
        outcome: AttemptOutcome,
        startedNanos: Long,
        firstTokenNanos: Long?,
    ) {
        val durationNanos = System.nanoTime() - startedNanos
        val outcomeName = outcomeName(outcome)
        val tags = arrayOf(
            "vendor", context.deployment.vendor.name.lowercase(),
            "model_group", context.deployment.modelGroup.value,
            "stream", context.streaming.toString(),
            "outcome", outcomeName,
        )

        Counter.builder("llm.gateway.attempts")
            .description("Provider attempts made by the gateway")
            .tags(
                "vendor", context.deployment.vendor.name.lowercase(),
                "model_group", context.deployment.modelGroup.value,
                "outcome", outcomeName,
            )
            .register(meterRegistry)
            .increment()

        if (context.kind == AttemptKind.FALLBACK) {
            Counter.builder("llm.gateway.fallbacks")
                .description("Provider attempts caused by switching deployment")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "model_group", context.deployment.modelGroup.value)
                .register(meterRegistry)
                .increment()
        }
        if (context.kind == AttemptKind.RETRY) {
            Counter.builder("llm.gateway.retries")
                .description("Provider retries on the same deployment")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "model_group", context.deployment.modelGroup.value)
                .register(meterRegistry)
                .increment()
        }

        durationNanos.let {
            Timer.builder("llm.gateway.attempt.duration")
                .description("Provider attempt duration")
                .tags(*tags)
                .register(meterRegistry)
                .record(it, TimeUnit.NANOSECONDS)
        }
        firstTokenNanos?.let { first ->
            Timer.builder("llm.gateway.attempt.ttft")
                .description("Time to first streamed token")
                .tags(
                    "vendor", context.deployment.vendor.name.lowercase(),
                    "model_group", context.deployment.modelGroup.value,
                )
                .register(meterRegistry)
                .record(first - startedNanos, TimeUnit.NANOSECONDS)
        }

        val usage = usageOf(outcome)
        val cost = costOf(outcome)
        val costTags = arrayOf(
            "vendor", context.deployment.vendor.name.lowercase(),
            "model_group", context.deployment.modelGroup.value,
            "status", cost.status.name.lowercase(),
            "source", cost.source.name.lowercase(),
        )
        recordTokens(context, usage)
        recordComponents(context, usage)
        if (usage.components.isEmpty() || usage.components.any { it.quantity == null }) {
            Counter.builder("llm.gateway.usage.missing")
                .description("Provider attempts completed without usage metadata")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "model_group", context.deployment.modelGroup.value)
                .register(meterRegistry)
                .increment()
        }

        if (cost.amount != null) {
            DistributionSummary.builder("llm.gateway.cost.usd")
                .description("Known cost subtotal per attempt, separated by completeness and source")
                .baseUnit("usd")
                .tags(*costTags)
                .register(meterRegistry)
                .record(cost.usd.toDouble())
        }

        if (cost.status == CostStatus.UNKNOWN || cost.status == CostStatus.PARTIAL) {
            Counter.builder("llm.gateway.cost.unknown")
                .description("Attempts whose cost is unknown or partial")
                .tags(
                    "vendor", context.deployment.vendor.name.lowercase(),
                    "model_group", context.deployment.modelGroup.value,
                    "status", cost.status.name.lowercase(),
                )
                .register(meterRegistry)
                .increment()
        } else {
            Counter.builder("llm.gateway.cost.usd.total")
                .description("Cumulative complete cost observations; not an authoritative billing ledger")
                .baseUnit("usd")
                .tags(*costTags)
                .register(meterRegistry)
                .increment(cost.usd.toDouble())
        }

        logger.info(
            "llm_gateway_attempt request_id={} execution_id={} trace_id={} attempt_id={} attempt_kind={} caller={} tenant={} vendor={} deployment={} provider_request_id={} sequence={} outcome={} duration_ms={} ttft_ms={} input_tokens={} output_tokens={} cache_read_tokens={} cache_write_tokens={} cost_usd={} cost_status={} cost_source={} pricing_version={} warnings={}",
            context.requestId.value,
            context.executionId.value,
            context.traceId ?: "-",
            context.attemptId.value,
            context.kind.name.lowercase(),
            context.caller,
            context.tenant,
            context.deployment.vendor.name.lowercase(),
            context.deployment.id.value,
            providerRequestIdOf(outcome) ?: "-",
            context.sequence,
            outcomeName,
            TimeUnit.NANOSECONDS.toMillis(durationNanos),
            firstTokenNanos?.let { TimeUnit.NANOSECONDS.toMillis(it - startedNanos) } ?: "-",
            usage.quantity(UsageType.INPUT_TOKENS) ?: "-",
            usage.quantity(UsageType.OUTPUT_TOKENS) ?: "-",
            usage.quantity(UsageType.CACHE_READ_INPUT_TOKENS) ?: "-",
            usage.quantity(UsageType.CACHE_WRITE_INPUT_TOKENS) ?: "-",
            cost.amount ?: "-",
            cost.status.name.lowercase(),
            cost.source.name.lowercase(),
            cost.pricingVersion ?: "-",
            cost.warnings.joinToString(",").ifBlank { "-" },
        )
    }

    private fun logObserverFailure(context: AttemptContext, error: Exception) {
        logger.warn(
            "llm_gateway_observation_failed attempt_id={} error_type={}",
            context.attemptId.value,
            error.javaClass.simpleName,
        )
    }

    private fun recordTokens(context: AttemptContext, usage: Usage) {
        val vendor = context.deployment.vendor.name.lowercase()
        listOf("input" to UsageType.INPUT_TOKENS, "output" to UsageType.OUTPUT_TOKENS).forEach { (direction, type) ->
            usage.quantity(type)?.let { count ->
                Counter.builder("llm.gateway.tokens")
                    .description("Provider tokens observed by the gateway")
                    .tags("vendor", vendor, "direction", direction)
                    .register(meterRegistry)
                    .increment(count.toDouble())
            }
        }
        listOf(
            "input_total" to UsageType.INPUT_TOKENS,
            "output_total" to UsageType.OUTPUT_TOKENS,
            "cache_read" to UsageType.CACHE_READ_INPUT_TOKENS,
            "cache_write" to UsageType.CACHE_WRITE_INPUT_TOKENS,
            "reasoning_output" to UsageType.REASONING_OUTPUT_TOKENS,
        ).forEach { (label, type) ->
            usage.quantity(type)?.let { count ->
                Counter.builder("llm.gateway.tokens.total")
                    .description("Provider tokens by normalized token type; includes non-additive detail")
                    .tags("vendor", vendor, "model_group", context.deployment.modelGroup.value, "token_type", label)
                    .register(meterRegistry)
                    .increment(count.toDouble())
            }
        }
    }

    private fun recordComponents(context: AttemptContext, usage: Usage) {
        usage.components.filter { it.quantity != null }.groupBy { it.key.type to it.source }
            .forEach { (identity, components) ->
                val (type, source) = identity
                Counter.builder("llm.gateway.usage.units")
                    .description("Measured usage by unit, type and source; token detail is non-additive")
                    .tags(
                        "vendor", context.deployment.vendor.name.lowercase(),
                        "model_group", context.deployment.modelGroup.value,
                        "usage_type", type.name.lowercase(),
                        "unit", type.unit.name.lowercase(),
                        "source", source.name.lowercase(),
                    )
                    .register(meterRegistry)
                    .increment(components.sumOf { requireNotNull(it.quantity) }.toDouble())
            }
    }

    private fun usageOf(outcome: AttemptOutcome): Usage = when (outcome) {
        is AttemptSuccess -> outcome.usage
        is AttemptFailure -> outcome.usage
        is AttemptCancelledWithUsage -> outcome.usage
        AttemptCancelled -> Usage(emptyList())
    }

    private fun costOf(outcome: AttemptOutcome) = when (outcome) {
        is AttemptSuccess -> outcome.cost
        is AttemptFailure -> outcome.cost
        is AttemptCancelledWithUsage -> outcome.cost
        AttemptCancelled -> com.example.llmgateway.domain.accounting.Cost()
    }

    private fun outcomeName(outcome: AttemptOutcome): String = when (outcome) {
        is AttemptSuccess -> "success"
        is AttemptFailure -> outcome.failureClass.name.lowercase()
        is AttemptCancelledWithUsage -> "cancelled"
        AttemptCancelled -> "cancelled"
    }

    private fun providerRequestIdOf(outcome: AttemptOutcome): String? = when (outcome) {
        is AttemptSuccess -> outcome.providerRequestId
        is AttemptFailure -> outcome.providerRequestId
        is AttemptCancelledWithUsage -> outcome.providerRequestId
        AttemptCancelled -> null
    }

}
