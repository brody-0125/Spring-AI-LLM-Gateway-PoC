package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.application.port.out.RequestObserverPort
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.execution.RequestOutcomeStatus
import com.example.llmgateway.domain.observation.NoOpObservationHandle
import com.example.llmgateway.domain.observation.ObservationHandle
import com.example.llmgateway.domain.observation.RequestObservationContext
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

class MicrometerRequestObserver(
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
) : RequestObserverPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    override fun start(metadata: RequestObservationContext): ObservationHandle<RequestOutcome> {
        val context = metadata.requestContext
        val observation = Observation.createNotStarted("llm.gateway.request", observationRegistry)
            .lowCardinalityKeyValue("stream", metadata.streaming.toString())
        return try {
            observation.start()
            StartedObservation(observation, observationRegistry, { logObserverFailure(context, it) }) { outcome, startedNanos ->
                observation.lowCardinalityKeyValue("outcome", outcome.status.name.lowercase())
                recordStop(metadata, outcome, startedNanos)
            }
        } catch (error: Exception) {
            runCatching { observation.stop() }
            logObserverFailure(context, error)
            NoOpObservationHandle
        }
    }

    private fun recordStop(
        metadata: RequestObservationContext,
        outcome: RequestOutcome,
        startedNanos: Long,
    ) {
        val context = metadata.requestContext
        try {
            val outcomeName = outcome.status.name.lowercase()
            val tags = arrayOf(
                "stream", metadata.streaming.toString(),
                "outcome", outcomeName,
            )
            Counter.builder("llm.gateway.requests")
                .description("Requests processed by the gateway")
                .tags(*tags)
                .register(meterRegistry)
                .increment()
            Timer.builder("llm.gateway.request.duration")
                .description("End-to-end gateway request duration")
                .tags(*tags)
                .register(meterRegistry)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS)
            if (outcome.status == RequestOutcomeStatus.FAILURE) {
                Counter.builder("llm.gateway.request.failures")
                    .description("Requests that ended in a gateway failure")
                    .tags(
                        "stream", metadata.streaming.toString(),
                        "error_type", outcome.errorType ?: "unknown",
                    )
                    .register(meterRegistry)
                    .increment()
            }
            logger.info(
                "llm_gateway_request request_id={} execution_id={} trace_id={} caller={} tenant={} model_group={} stream={} outcome={} error_type={} error_code={}",
                context.requestId.value,
                context.executionId.value,
                context.traceId ?: "-",
                context.caller,
                context.tenant,
                metadata.modelGroup.value,
                metadata.streaming,
                outcomeName,
                outcome.errorType ?: "-",
                outcome.errorCode ?: "-",
            )
        } catch (error: Exception) {
            logObserverFailure(context, error)
        }
    }

    private fun logObserverFailure(context: RequestContext, error: Exception) {
        logger.warn(
            "llm_gateway_observation_failed request_id={} execution_id={} error_type={}",
            context.requestId.value,
            context.executionId.value,
            error.javaClass.simpleName,
        )
    }

}
