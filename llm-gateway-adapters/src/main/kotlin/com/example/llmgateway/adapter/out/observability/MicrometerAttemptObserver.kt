package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.AttemptOutcome
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class MicrometerAttemptObserver(
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
) : AttemptObserverPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val observations = ConcurrentHashMap<String, Observation>()

    override fun onStart(context: AttemptContext) {
        val observation = Observation.createNotStarted("llm.gateway.attempt", observationRegistry)
            .lowCardinalityKeyValue("vendor", context.deployment.vendor.name.lowercase())
            .lowCardinalityKeyValue("model_group", context.deployment.modelGroup.value)
            .lowCardinalityKeyValue("stream", context.streaming.toString())
            .start()
        observations[context.attemptId.value] = observation
    }

    override fun onStop(context: AttemptContext, outcome: AttemptOutcome) {
        observations.remove(context.attemptId.value)?.stop()
        val outcomeName = outcomeName(outcome)
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

        if (outcome is AttemptOutcome.Success) {
            Counter.builder("llm.gateway.tokens")
                .description("Provider tokens observed by the gateway")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "direction", "input")
                .register(meterRegistry)
                .increment(outcome.usage.inputTokens.toDouble())
            Counter.builder("llm.gateway.tokens")
                .description("Provider tokens observed by the gateway")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "direction", "output")
                .register(meterRegistry)
                .increment(outcome.usage.outputTokens.toDouble())
            DistributionSummary.builder("llm.gateway.cost.usd")
                .description("Estimated provider cost in USD")
                .baseUnit("usd")
                .tags("vendor", context.deployment.vendor.name.lowercase(), "model_group", context.deployment.modelGroup.value)
                .register(meterRegistry)
                .record(outcome.cost.usd.toDouble())
        }

        logger.info(
            "llm_gateway_attempt request_id={} trace_id={} attempt_id={} caller={} tenant={} vendor={} deployment={} sequence={} outcome={} input_tokens={} output_tokens={} cost_usd={}",
            context.requestId.value,
            context.traceId ?: "-",
            context.attemptId.value,
            context.caller,
            context.tenant,
            context.deployment.vendor.name.lowercase(),
            context.deployment.id.value,
            context.sequence,
            outcomeName,
            (outcome as? AttemptOutcome.Success)?.usage?.inputTokens ?: 0,
            (outcome as? AttemptOutcome.Success)?.usage?.outputTokens ?: 0,
            (outcome as? AttemptOutcome.Success)?.cost?.usd ?: "0",
        )
    }

    private fun outcomeName(outcome: AttemptOutcome): String = when (outcome) {
        is AttemptOutcome.Success -> "success"
        is AttemptOutcome.Failure -> outcome.failureClass.name.lowercase()
        AttemptOutcome.Cancelled -> "cancelled"
    }
}
