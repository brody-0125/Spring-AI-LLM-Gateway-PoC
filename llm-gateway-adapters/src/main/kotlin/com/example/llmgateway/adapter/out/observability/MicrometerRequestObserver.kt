package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.application.port.out.RequestObserverPort
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RequestOutcome
import com.example.llmgateway.domain.model.RequestOutcomeStatus
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MicrometerRequestObserver(
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
) : RequestObserverPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val observations = ConcurrentHashMap<String, StartedObservation>()

    override fun onStart(context: RequestContext, request: CanonicalChatRequest) {
        try {
            val observation = Observation.createNotStarted("llm.gateway.request", observationRegistry)
                .lowCardinalityKeyValue("stream", request.stream.toString())
                .start()
            observations[context.requestId.value] = StartedObservation(observation, System.nanoTime())
        } catch (error: Exception) {
            logObserverFailure(context, error)
        }
    }

    override fun onStop(
        context: RequestContext,
        request: CanonicalChatRequest,
        outcome: RequestOutcome,
    ) {
        val started = observations.remove(context.requestId.value)
        try {
            started?.observation?.stop()
            val outcomeName = outcome.status.name.lowercase()
            val tags = arrayOf(
                "stream", request.stream.toString(),
                "outcome", outcomeName,
            )
            Counter.builder("llm.gateway.requests")
                .description("Requests processed by the gateway")
                .tags(*tags)
                .register(meterRegistry)
                .increment()
            started?.let {
                Timer.builder("llm.gateway.request.duration")
                    .description("End-to-end gateway request duration")
                    .tags(*tags)
                    .register(meterRegistry)
                    .record(System.nanoTime() - it.startedNanos, TimeUnit.NANOSECONDS)
            }
            if (outcome.status == RequestOutcomeStatus.FAILURE) {
                Counter.builder("llm.gateway.request.failures")
                    .description("Requests that ended in a gateway failure")
                    .tags(
                        "stream", request.stream.toString(),
                        "error_type", outcome.errorType ?: "unknown",
                    )
                    .register(meterRegistry)
                    .increment()
            }
            logger.info(
                "llm_gateway_request request_id={} trace_id={} caller={} tenant={} model_group={} stream={} outcome={} error_type={} error_code={}",
                context.requestId.value,
                context.traceId ?: "-",
                context.caller,
                context.tenant,
                request.modelGroup.value,
                request.stream,
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
            "llm_gateway_observation_failed request_id={} error_type={}",
            context.requestId.value,
            error.javaClass.simpleName,
        )
    }

    private data class StartedObservation(
        val observation: Observation,
        val startedNanos: Long,
    )
}
