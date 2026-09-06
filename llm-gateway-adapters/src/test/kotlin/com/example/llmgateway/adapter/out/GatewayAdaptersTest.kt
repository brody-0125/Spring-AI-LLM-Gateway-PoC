package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.admission.ConfigurableGuardrailAdapter
import com.example.llmgateway.adapter.out.observability.MicrometerAttemptObserver
import com.example.llmgateway.adapter.out.security.StaticApiKeyAuthenticationAdapter
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.CanonicalMessage
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.GatewayPrincipal
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.AttemptOutcome
import com.example.llmgateway.domain.model.Cost
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import com.example.llmgateway.core.primitive.AttemptId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GatewayAdaptersTest : FunSpec() {

    private val deployment = Deployment(
        id = DeploymentId("openai-a"),
        vendor = Vendor.OPENAI,
        dialect = Dialect.OPENAI,
        modelGroup = ModelGroup("default"),
        model = "gpt-test",
    )

    private val request = CanonicalChatRequest(
        modelGroup = ModelGroup("default"),
        messages = listOf(CanonicalMessage(com.example.llmgateway.core.primitive.MessageRole.USER, "hello")),
    )

    init {
        test("configured guardrail rejects blocked phrase and oversized input") {
            val blocked = ConfigurableGuardrailAdapter(true, 100, listOf("secret instruction"))
            blocked.inspect(
                request.copy(messages = listOf(CanonicalMessage(com.example.llmgateway.core.primitive.MessageRole.USER, "SECRET INSTRUCTION"))),
                RequestContext(RequestId("req")),
            ).allowed shouldBe false

            val oversized = ConfigurableGuardrailAdapter(true, 3, emptyList())
            oversized.inspect(request, RequestContext(RequestId("req"))).reason shouldBe
                "The request exceeds the gateway input limit"

            ConfigurableGuardrailAdapter(false, 1, listOf("hello"))
                .inspect(request, RequestContext(RequestId("req"))) shouldBe
                com.example.llmgateway.domain.model.GuardrailDecision.ALLOWED
        }

        test("api key authentication returns caller tenant and administrator flag") {
            val authentication = StaticApiKeyAuthenticationAdapter(
                enabled = true,
                clients = mapOf("secret" to GatewayPrincipal("bff", "tenant-a", administrator = true)),
            )

            authentication.authenticate("Bearer secret") shouldBe GatewayPrincipal("bff", "tenant-a", true)
            authentication.authenticate("Bearer wrong") shouldBe null
            authentication.authenticate("Basic secret") shouldBe null
            authentication.authenticate("Bearer ") shouldBe null
        }

        test("attempt observer records success tokens, cost, and fallback metrics") {
            val meters = SimpleMeterRegistry()
            val observer = MicrometerAttemptObserver(ObservationRegistry.create(), meters)
            val context = AttemptContext(
                requestId = RequestId("req"),
                attemptId = AttemptId("attempt"),
                sequence = 2,
                deployment = deployment,
                caller = "bff",
                tenant = "tenant-a",
                traceId = "trace",
            )

            observer.onStart(context)
            observer.onStop(context, AttemptOutcome.Success(
                usage = com.example.llmgateway.domain.model.Usage(inputTokens = 2, outputTokens = 3),
                cost = Cost(java.math.BigDecimal("0.01")),
            ))

            meters.counter(
                "llm.gateway.attempts",
                "vendor", "openai", "model_group", "default", "outcome", "success",
            ).count() shouldBe 1.0
            meters.counter(
                "llm.gateway.fallbacks",
                "vendor", "openai", "model_group", "default",
            ).count() shouldBe 1.0
            meters.counter(
                "llm.gateway.tokens",
                "vendor", "openai", "direction", "input",
            ).count() shouldBe 2.0
            meters.counter(
                "llm.gateway.tokens",
                "vendor", "openai", "direction", "output",
            ).count() shouldBe 3.0
            meters.summary(
                "llm.gateway.cost.usd",
                "vendor", "openai", "model_group", "default",
            ).totalAmount() shouldBe 0.01
        }
    }
}
