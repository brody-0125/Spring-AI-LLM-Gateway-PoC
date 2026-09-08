package com.example.llmgateway.application

import com.example.llmgateway.application.operator.RequestLifecycleOperator
import com.example.llmgateway.application.port.out.RequestAccountingPort
import com.example.llmgateway.application.port.out.RequestObserverPort
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.CanonicalMessage
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RequestOutcome
import com.example.llmgateway.domain.model.RequestOutcomeStatus
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

    test("preserves the request failure when telemetry sinks fail") {
        val lifecycle = RequestLifecycleOperator(
            observer = object : RequestObserverPort {
                override fun onStart(context: RequestContext, request: CanonicalChatRequest) = error("observer start")
                override fun onStop(
                    context: RequestContext,
                    request: CanonicalChatRequest,
                    outcome: RequestOutcome,
                ) = error("observer stop")
            },
            accounting = object : RequestAccountingPort {
                override fun record(
                    context: RequestContext,
                    request: CanonicalChatRequest,
                    outcome: RequestOutcome,
                ) = error("accounting failure")
            },
        )

        shouldThrow<IllegalStateException> {
            lifecycle.execute(request, context) { error("provider failure") }
        }.message shouldBe "provider failure"
    }
})

private class RecordingRequestObserver(
    private val events: MutableList<String>,
) : RequestObserverPort {
    override fun onStart(context: RequestContext, request: CanonicalChatRequest) {
        events += "start"
    }

    override fun onStop(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) {
        events += "stop"
    }
}

private class RecordingRequestAccounting(
    private val outcomes: MutableList<RequestOutcome>,
) : RequestAccountingPort {
    override fun record(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) {
        outcomes += outcome
    }
}
