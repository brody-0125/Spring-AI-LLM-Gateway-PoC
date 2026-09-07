package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.AttemptOutcome
import com.example.llmgateway.domain.model.CostStatus
import com.example.llmgateway.domain.model.Usage
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MicrometerAttemptObserver(
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
) : AttemptObserverPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val observations = ConcurrentHashMap<String, StartedObservation>()
    private val firstTokens = ConcurrentHashMap<String, Long>()

    override fun onStart(context: AttemptContext) {
        try {
            val observation = Observation.createNotStarted("llm.gateway.attempt", observationRegistry)
                .lowCardinalityKeyValue("vendor", context.deployment.vendor.name.lowercase())
                .lowCardinalityKeyValue("model_group", context.deployment.modelGroup.value)
                .lowCardinalityKeyValue("stream", context.streaming.toString())
                .start()
            observations[context.attemptId.value] = StartedObservation(observation, System.nanoTime())
        } catch (error: Exception) {
            logObserverFailure(context, error)
        }
    }

    override fun onFirstToken(context: AttemptContext) {
        try {
            firstTokens.putIfAbsent(context.attemptId.value, System.nanoTime())
        } catch (error: Exception) {
            logObserverFailure(context, error)
        }
    }

    override fun onStop(context: AttemptContext, outcome: AttemptOutcome) {
        val started = observations.remove(context.attemptId.value)
        val firstTokenNanos = firstTokens.remove(context.attemptId.value)
        try {
            started?.observation?.stop()
            recordStop(context, outcome, started, firstTokenNanos)
        } catch (error: Exception) {
            logObserverFailure(context, error)
        }
    }

    private fun recordStop(
        context: AttemptContext,
        outcome: AttemptOutcome,
        started: StartedObservation?,
        firstTokenNanos: Long?,
    ) {
        val durationNanos = started?.let { System.nanoTime() - it.startedNanos }
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

        if (context.sequence > 1) {
            Counter.builder("llm.gateway.fallbacks")
                .description("Provider attempts made after the primary route")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "model_group", context.deployment.modelGroup.value)
                .register(meterRegistry)
                .increment()
        }

        durationNanos?.let {
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
                .record(first - (started?.startedNanos ?: first), TimeUnit.NANOSECONDS)
        }

        val usage = usageOf(outcome)
        val cost = costOf(outcome)
        if (usage.available) {
            recordTokens(context, usage)
        } else {
            Counter.builder("llm.gateway.usage.missing")
                .description("Provider attempts completed without usage metadata")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "model_group", context.deployment.modelGroup.value)
                .register(meterRegistry)
                .increment()
        }

        if (cost.usd.signum() > 0 || cost.status == CostStatus.REPORTED || cost.status == CostStatus.ESTIMATED) {
            DistributionSummary.builder("llm.gateway.cost.usd")
                .description("Provider cost per attempt")
                .baseUnit("usd")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "model_group", context.deployment.modelGroup.value)
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
                .description("Cumulative provider cost for accounted attempts")
                .baseUnit("usd")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "model_group", context.deployment.modelGroup.value)
                .register(meterRegistry)
                .increment(cost.usd.toDouble())
        }

        logger.info(
            "llm_gateway_attempt request_id={} trace_id={} attempt_id={} caller={} tenant={} vendor={} deployment={} sequence={} outcome={} duration_ms={} ttft_ms={} input_tokens={} output_tokens={} cache_read_tokens={} cache_write_tokens={} cost_usd={} cost_status={} pricing_version={} warnings={}",
            context.requestId.value,
            context.traceId ?: "-",
            context.attemptId.value,
            context.caller,
            context.tenant,
            context.deployment.vendor.name.lowercase(),
            context.deployment.id.value,
            context.sequence,
            outcomeName,
            durationNanos?.let { TimeUnit.NANOSECONDS.toMillis(it) } ?: "-",
            firstTokenNanos?.let { TimeUnit.NANOSECONDS.toMillis(it - (started?.startedNanos ?: it)) } ?: "-",
            usage.inputTokens,
            usage.outputTokens,
            usage.cacheReadInputTokens,
            usage.cacheWriteInputTokens,
            cost.usd,
            cost.status.name.lowercase(),
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
        Counter.builder("llm.gateway.tokens")
            .description("Provider tokens observed by the gateway")
            .tags("vendor", vendor, "direction", "input")
            .register(meterRegistry)
            .increment(usage.inputTokens.toDouble())
        Counter.builder("llm.gateway.tokens")
            .description("Provider tokens observed by the gateway")
            .tags("vendor", vendor, "direction", "output")
            .register(meterRegistry)
            .increment(usage.outputTokens.toDouble())

        listOf(
            "input_total" to usage.inputTokens,
            "output_total" to usage.outputTokens,
            "cache_read" to usage.cacheReadInputTokens,
            "cache_write" to usage.cacheWriteInputTokens,
            "reasoning_output" to usage.reasoningOutputTokens,
        ).forEach { (type, count) ->
            Counter.builder("llm.gateway.tokens.total")
                .description("Provider tokens by normalized token type")
                .tags("vendor", vendor, "model_group", context.deployment.modelGroup.value, "token_type", type)
                .register(meterRegistry)
                .increment(count.toDouble())
        }
    }

    private fun usageOf(outcome: AttemptOutcome): Usage = when (outcome) {
        is AttemptOutcome.Success -> outcome.usage
        is AttemptOutcome.Failure -> outcome.usage
        is AttemptOutcome.CancelledWithUsage -> outcome.usage
        AttemptOutcome.Cancelled -> Usage(available = false)
    }

    private fun costOf(outcome: AttemptOutcome) = when (outcome) {
        is AttemptOutcome.Success -> outcome.cost
        is AttemptOutcome.Failure -> outcome.cost
        is AttemptOutcome.CancelledWithUsage -> outcome.cost
        AttemptOutcome.Cancelled -> com.example.llmgateway.domain.model.Cost()
    }

    private fun outcomeName(outcome: AttemptOutcome): String = when (outcome) {
        is AttemptOutcome.Success -> "success"
        is AttemptOutcome.Failure -> outcome.failureClass.name.lowercase()
        is AttemptOutcome.CancelledWithUsage -> "cancelled"
        AttemptOutcome.Cancelled -> "cancelled"
    }

    private data class StartedObservation(
        val observation: Observation,
        val startedNanos: Long,
    )
}
