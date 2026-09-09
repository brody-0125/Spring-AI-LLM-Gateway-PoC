package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.admission.ConfigurableInputGuardrailAdapter
import com.example.llmgateway.adapter.out.admission.ConfigurableOutputGuardrailAdapter
import com.example.llmgateway.adapter.out.observability.MicrometerAttemptObserver
import com.example.llmgateway.adapter.out.observability.MicrometerRequestObserver
import com.example.llmgateway.adapter.out.security.StaticApiKeyAuthenticationAdapter
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ExecutionId
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.execution.RequestOutcomeStatus
import com.example.llmgateway.domain.identity.GatewayPrincipal
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.CanonicalMessage
import com.example.llmgateway.domain.observation.AttemptObservationHandle
import com.example.llmgateway.domain.observation.RequestObservationContext
import com.example.llmgateway.domain.routing.Deployment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry

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
            val blocked = ConfigurableInputGuardrailAdapter(true, 100, listOf("secret instruction"))
            blocked.inspect(
                request.copy(messages = listOf(CanonicalMessage(com.example.llmgateway.core.primitive.MessageRole.USER, "SECRET INSTRUCTION"))),
                RequestContext(RequestId("req")),
            ).allowed shouldBe false

            val oversized = ConfigurableInputGuardrailAdapter(true, 3, emptyList())
            oversized.inspect(request, RequestContext(RequestId("req"))).reason shouldBe
                "The request exceeds the gateway input limit"

            val outputBlocked = ConfigurableOutputGuardrailAdapter(
                enabled = true,
                maxOutputCharacters = 20,
                blockedPhrases = listOf("internal-only"),
            )
            outputBlocked.inspect("internal-only", RequestContext(RequestId("req"))).code shouldBe
                "OUTPUT_POLICY_BLOCKED"
            outputBlocked.inspect("123456789012345678901", RequestContext(RequestId("req"))).code shouldBe
                "OUTPUT_TOO_LARGE"

            ConfigurableInputGuardrailAdapter(false, 1, listOf("hello"))
                .inspect(request, RequestContext(RequestId("req"))) shouldBe
                com.example.llmgateway.domain.policy.GuardrailDecision.ALLOWED
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
                executionId = ExecutionId.newId(),
                kind = AttemptKind.FALLBACK,
                sequence = 2,
                deployment = deployment,
                caller = "bff",
                tenant = "tenant-a",
                traceId = "trace",
            )

            val handle = observer.start(context)
            handle.stop(AttemptSuccess(
                usage = com.example.llmgateway.domain.accounting.Usage(inputTokens = 2, outputTokens = 3),
                cost = Cost(
                    java.math.BigDecimal("0.01"),
                    status = com.example.llmgateway.domain.accounting.CostStatus.ESTIMATED,
                    source = com.example.llmgateway.domain.accounting.CostSource.RATE_CARD,
                ),
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
                "status", "estimated", "source", "rate_card",
            ).totalAmount() shouldBe 0.01
        }

        test("same correlation across overlapping executions keeps every observation lifetime") {
            val meters = SimpleMeterRegistry()
            val registry = ObservationRegistry.create()
            val starts = java.util.concurrent.atomic.AtomicInteger()
            val stops = java.util.concurrent.atomic.AtomicInteger()
            registry.observationConfig().observationHandler(
                object : io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context> {
                    override fun supportsContext(context: io.micrometer.observation.Observation.Context) = true
                    override fun onStart(context: io.micrometer.observation.Observation.Context) { starts.incrementAndGet() }
                    override fun onStop(context: io.micrometer.observation.Observation.Context) { stops.incrementAndGet() }
                },
            )
            val observer = MicrometerRequestObserver(registry, meters)
            val contexts = (1..20).map {
                RequestContext(RequestId("shared-correlation"), tenant = "tenant-${it % 2}")
            }
            contexts.map { it.executionId }.toSet().size shouldBe 20
            val ready = java.util.concurrent.CountDownLatch(contexts.size)
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                contexts.map { context ->
                    executor.submit {
                        val handle = observer.start(RequestObservationContext(context, request.modelGroup, request.stream))
                        ready.countDown()
                        check(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        val outcome = RequestOutcome(RequestOutcomeStatus.SUCCESS)
                        handle.stop(outcome)
                        handle.stop(outcome)
                    }
                }.forEach { it.get() }
            }
            starts.get() shouldBe 20
            stops.get() shouldBe 20
            meters.get("llm.gateway.request.duration").timer().count() shouldBe 20L
            meters.get("llm.gateway.requests").counter().count() shouldBe 20.0
            meters.meters.flatMap { it.id.tags }.any { it.key in setOf("execution_id", "request_id") } shouldBe false
        }

        test("attempt retry and fallback metrics follow kind rather than sequence") {
            val meters = SimpleMeterRegistry()
            val observer = MicrometerAttemptObserver(ObservationRegistry.create(), meters)
            val executionId = ExecutionId.newId()
            listOf(AttemptKind.INITIAL, AttemptKind.RETRY, AttemptKind.RETRY, AttemptKind.FALLBACK)
                .forEachIndexed { index, kind ->
                    val context = AttemptContext(
                        requestId = RequestId("correlation"),
                        executionId = executionId,
                        attemptId = AttemptId("attempt-$index"),
                        sequence = index + 1,
                        kind = kind,
                        deployment = deployment,
                    )
                    val handle = observer.start(context)
                    handle.stop(AttemptSuccess(
                        com.example.llmgateway.domain.accounting.Usage(), Cost(),
                    ))
                }
            meters.get("llm.gateway.attempts").counter().count() shouldBe 4.0
            meters.get("llm.gateway.retries").counter().count() shouldBe 2.0
            meters.get("llm.gateway.fallbacks").counter().count() shouldBe 1.0
        }

        test("request observer records lifecycle metrics without recording prompt content") {
            val meters = SimpleMeterRegistry()
            val observer = MicrometerRequestObserver(ObservationRegistry.create(), meters)
            val context = RequestContext(RequestId("request-observer"), caller = "bff", tenant = "tenant-a")

            val handle = observer.start(RequestObservationContext(context, request.modelGroup, request.stream))
            handle.stop(
                RequestOutcome(
                    status = RequestOutcomeStatus.FAILURE,
                    errorType = "gateway_timeout",
                    errorCode = "GATEWAY_TIMEOUT",
                ),
            )

            meters.counter(
                "llm.gateway.requests",
                "stream", "false", "outcome", "failure",
            ).count() shouldBe 1.0
            meters.counter(
                "llm.gateway.request.failures",
                "stream", "false", "error_type", "gateway_timeout",
            ).count() shouldBe 1.0
            meters.find("llm.gateway.requests").meters().first().id.tags.any { it.value == "hello" } shouldBe false
        }
    }
}
