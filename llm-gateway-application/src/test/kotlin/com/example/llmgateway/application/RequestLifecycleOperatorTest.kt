package com.example.llmgateway.application

import com.example.llmgateway.application.operator.RequestLifecycleOperator
import com.example.llmgateway.application.port.out.RequestAccountingPort
import com.example.llmgateway.application.port.out.RequestObserverPort
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.execution.RequestOutcomeStatus
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.CanonicalMessage
import com.example.llmgateway.domain.observation.ObservationHandle
import com.example.llmgateway.domain.observation.RequestObservationContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class RequestLifecycleOperatorTest : FunSpec({
    val request = CanonicalChatRequest(
        modelGroup = ModelGroup("default"),
        messages = listOf(CanonicalMessage(MessageRole.USER, "hello")),
    )
    val context = RequestContext(
        requestId = RequestId("req-lifecycle"),
        startedAt = Instant.parse("2026-01-01T00:00:00Z"),
        deadline = Instant.parse("2026-01-01T00:01:00Z"),
    )

    test("records a successful request after the operation completes") {
        val events = mutableListOf<String>()
        val outcomes = mutableListOf<RequestOutcome>()
        val lifecycle = RequestLifecycleOperator(
            observer = RecordingRequestObserver(events),
            accounting = RecordingRequestAccounting(outcomes),
        )

        lifecycle.execute(request, context) { "ok" } shouldBe "ok"
        events shouldBe listOf("start", "stop")
        outcomes.single().status shouldBe RequestOutcomeStatus.SUCCESS
    }

    for (streaming in listOf(false, true)) {
        test("known first-attempt admission denial survives failed execution recording streaming=$streaming") {
            val observed = mutableListOf<RequestOutcome>()
            val denied = GatewayException(com.example.llmgateway.domain.error.GatewayError(
                type = "admission_unavailable", code = "ADMISSION_UNAVAILABLE",
                category = com.example.llmgateway.domain.error.ErrorCategory.TRANSIENT,
                retryable = true, message = "fixture admission failure", requestId = context.requestId,
            ))
            val lifecycle = RequestLifecycleOperator(
                observer = object : RequestObserverPort {
                    override fun start(metadata: RequestObservationContext) = object : ObservationHandle<RequestOutcome> {
                        override fun stop(outcome: RequestOutcome) { observed += outcome }
                    }
                },
                accounting = object : RequestAccountingPort {
                    override fun record(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) =
                        error("execution store unavailable")
                },
            )
            val failure = shouldThrow<GatewayException> {
                if (streaming) lifecycle.stream<String>(request.copy(stream = true), context) { throw denied }.use { it.toList() }
                else lifecycle.execute(request, context) { throw denied }
            }
            failure shouldBe denied
            failure.error.code shouldBe "ADMISSION_UNAVAILABLE"
            failure.suppressed.single().let { (it as GatewayException).error.code } shouldBe "OUTCOME_UNKNOWN"
            observed.single().errorCode shouldBe "ADMISSION_UNAVAILABLE"
        }
    }

    test("early stream close finishes the request lifecycle once as cancelled") {
        val events = mutableListOf<String>()
        val outcomes = mutableListOf<RequestOutcome>()
        val lifecycle = RequestLifecycleOperator(RecordingRequestObserver(events), RecordingRequestAccounting(outcomes))
        val stream = lifecycle.stream(request, context) {
            com.example.llmgateway.domain.stream.ManagedStream { sequenceOf(1, 2) }
        }
        stream.use { it.take(1).toList() shouldBe listOf(1) }
        stream.close()
        events shouldBe listOf("start", "stop")
        outcomes.single().status shouldBe RequestOutcomeStatus.CANCELLED
    }

    test("accounting failure cannot be hidden as the original provider failure") {
        val lifecycle = RequestLifecycleOperator(
            observer = object : RequestObserverPort {
                override fun start(metadata: RequestObservationContext): ObservationHandle<RequestOutcome> =
                    error("observer start")
            },
            accounting = object : RequestAccountingPort {
                override fun record(
                    context: RequestContext,
                    request: CanonicalChatRequest,
                    outcome: RequestOutcome,
                ) = error("accounting failure")
            },
        )

        val failure = shouldThrow<GatewayException> {
            lifecycle.execute(request, context) { error("provider failure") }
        }
        failure.error.code shouldBe "OUTCOME_UNKNOWN"
        failure.error.retryable shouldBe false
        failure.cause?.message shouldBe "accounting failure"
        failure.suppressed.single().message shouldBe "provider failure"
    }

    test("request write failure blocks success and always closes observation as unknown failure") {
        listOf(false, true).forEach { streaming ->
            val observed = mutableListOf<RequestOutcome>()
            var writes = 0
            val lifecycle = RequestLifecycleOperator(
                observer = object : RequestObserverPort {
                    override fun start(metadata: RequestObservationContext) = object : ObservationHandle<RequestOutcome> {
                        override fun stop(outcome: RequestOutcome) { observed += outcome }
                    }
                },
                accounting = object : RequestAccountingPort {
                    override fun record(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) {
                        writes++
                        error("write unavailable")
                    }
                },
            )
            val failure = shouldThrow<GatewayException> {
                if (streaming) lifecycle.stream(request.copy(stream = true), context) {
                    com.example.llmgateway.domain.stream.ManagedStream { sequenceOf("content") }
                }.use { it.toList() } else lifecycle.execute(request, context) { "ok" }
            }
            failure.error.code shouldBe "OUTCOME_UNKNOWN"
            writes shouldBe 1
            observed.single().status shouldBe RequestOutcomeStatus.FAILURE
            observed.single().errorCode shouldBe "OUTCOME_UNKNOWN"
        }
    }
})

private class RecordingRequestObserver(
    private val events: MutableList<String>,
) : RequestObserverPort {
    override fun start(metadata: RequestObservationContext): ObservationHandle<RequestOutcome> {
        events += "start"
        return object : ObservationHandle<RequestOutcome> {
            private val stopped = java.util.concurrent.atomic.AtomicBoolean()
            override fun stop(outcome: RequestOutcome) {
                if (stopped.compareAndSet(false, true)) events += "stop"
            }
        }
    }
}

private class RecordingRequestAccounting(
    private val outcomes: MutableList<RequestOutcome>,
) : RequestAccountingPort {
    override fun record(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) {
        outcomes += outcome
    }
}
