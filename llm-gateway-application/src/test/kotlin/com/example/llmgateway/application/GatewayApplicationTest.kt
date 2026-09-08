package com.example.llmgateway.application

import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.GuardrailPort
import com.example.llmgateway.application.port.out.RateLimiterPort
import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.application.operation.DefaultCompleteChatOperation
import com.example.llmgateway.application.operation.DefaultStreamChatOperation
import com.example.llmgateway.application.operator.DefaultCompleteAttemptOperator
import com.example.llmgateway.application.operator.DefaultStreamAttemptOperator
import com.example.llmgateway.application.operator.VirtualThreadDeadlineOperator
import com.example.llmgateway.application.port.`in`.ChatCompletionCommandIn
import com.example.llmgateway.application.port.`in`.ChatCompletionQueryIn
import com.example.llmgateway.application.operator.RequestAdmissionOperator
import com.example.llmgateway.application.policy.DefaultFailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.application.policy.AttemptPolicy
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.service.DefaultChatCompletionCommandService
import com.example.llmgateway.application.service.DefaultChatCompletionQueryService
import com.example.llmgateway.application.service.WeightedRoundRobinRoutePlanner
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.AttemptOutcome
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.CanonicalMessage
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.ProviderChunk
import com.example.llmgateway.domain.model.ProviderException
import com.example.llmgateway.domain.model.ProviderResponse
import com.example.llmgateway.domain.model.GuardrailDecision
import com.example.llmgateway.domain.model.RateLimitDecision
import com.example.llmgateway.domain.model.ProviderFailurePhase
import com.example.llmgateway.domain.model.RequestDisposition
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RoutingPlan
import com.example.llmgateway.domain.model.RoutingSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant

class GatewayApplicationTest : FunSpec() {

    private val openAi = Deployment(
        DeploymentId("openai-a"), Vendor.OPENAI, Dialect.OPENAI,
        ModelGroup("default"), "gpt-test-a",
    )
    private val openRouter = Deployment(
        DeploymentId("openrouter-a"), Vendor.OPENROUTER, Dialect.OPENAI_COMPATIBLE_OPENROUTER,
        ModelGroup("default"), "openrouter/test-b",
    )

    init {
        test("route planner excludes disabled, incompatible, and other-group deployments") {
            val disabled = openAi.copy(id = DeploymentId("disabled"), enabled = false)
            val nonStreaming = openRouter.copy(id = DeploymentId("non-streaming"), supportsStreaming = false)
            val otherGroup = openRouter.copy(id = DeploymentId("other-group"), modelGroup = ModelGroup("other"))
            val planner = WeightedRoundRobinRoutePlanner(
                FakeRegistry(listOf(disabled, nonStreaming, otherGroup, openAi)),
                NoOpCircuitBreaker,
            )

            planner.plan(request(stream = true), context()).candidates.map { it.id.value }
                .shouldContainExactly("openai-a")
        }

        test("round robin changes primary deployment") {
            val planner = WeightedRoundRobinRoutePlanner(
                FakeRegistry(listOf(openAi, openRouter)),
                NoOpCircuitBreaker,
            )

            planner.plan(request(), context()).primary.id shouldBe openAi.id
            planner.plan(request(), context()).primary.id shouldBe openRouter.id
        }

        test("weighted planner keeps alternates unique while distributing primary") {
            val weightedOpenAi = openAi.copy(weight = 2)
            val planner = WeightedRoundRobinRoutePlanner(
                FakeRegistry(listOf(weightedOpenAi, openRouter)),
                NoOpCircuitBreaker,
            )
            val primaries = (1..6).map { planner.plan(request(), context()).primary.id.value }

            primaries.count { it == "openai-a" } shouldBe 4
            primaries.count { it == "openrouter-a" } shouldBe 2
            planner.plan(request(), context()).candidates.map { it.id.value }
                .shouldContainExactly("openai-a", "openrouter-a")
        }

        test("planner excludes a previous deployment during re-evaluation") {
            val planner = WeightedRoundRobinRoutePlanner(
                FakeRegistry(listOf(openAi, openRouter)),
                NoOpCircuitBreaker,
            )

            planner.plan(request(), context(), setOf(openAi.id)).candidates.map { it.id.value }
                .shouldContainExactly("openrouter-a")
        }

        test("transient primary failure falls back to alternate") {
            val observer = RecordingObserver()
            val invoker = FakeInvoker(
                complete = { deployment ->
                    if (deployment.id == openAi.id) {
                        throw ProviderException(Vendor.OPENAI, 503, "overloaded", "temporary outage")
                    } else {
                        ProviderResponse("fallback response")
                    }
                },
            )
            val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, observer)

            gateway.query.complete(request(), context()).text shouldBe "fallback response"
            invoker.completeCalls.shouldContainExactly("openai-a", "openrouter-a")
            observer.outcomes shouldHaveSize 2
            (observer.outcomes[0] is AttemptOutcome.Failure) shouldBe true
            (observer.outcomes[1] is AttemptOutcome.Success) shouldBe true
        }

        test("rate limited primary failure falls back to alternate") {
            val invoker = FakeInvoker(
                complete = { deployment ->
                    if (deployment.id == openAi.id) {
                        throw ProviderException(Vendor.OPENAI, 429, "rate_limit", "too many requests")
                    } else {
                        ProviderResponse("rate limit fallback")
                    }
                },
            )
            val gateway = gateway(FixedPlanner(openAi, openRouter), invoker)

            gateway.query.complete(request(), context()).text shouldBe "rate limit fallback"
            invoker.completeCalls.shouldContainExactly("openai-a", "openrouter-a")
        }

        test("a request that was not sent is retried on the same deployment") {
            var firstCall = true
            val invoker = FakeInvoker(
                complete = { deployment ->
                    if (deployment.id == openAi.id && firstCall) {
                        firstCall = false
                        throw ProviderException(
                            vendor = Vendor.OPENAI,
                            statusCode = 503,
                            providerCode = "connection_failed",
                            message = "connection failed",
                            phase = ProviderFailurePhase.CONNECT,
                            requestDisposition = RequestDisposition.NOT_SENT,
                        )
                    }
                    ProviderResponse("recovered")
                },
            )
            val gateway = gateway(
                planner = FixedPlanner(openAi, openRouter),
                invoker = invoker,
                maxRetriesPerDeployment = 1,
            )

            gateway.query.complete(request(), context()).text shouldBe "recovered"
            invoker.completeCalls.shouldContainExactly("openai-a", "openai-a")
        }

        test("a provider response failure is not retried on the same deployment") {
            val invoker = FakeInvoker(
                complete = { deployment ->
                    if (deployment.id == openAi.id) {
                        throw ProviderException(Vendor.OPENAI, 503, "overloaded", "temporary outage")
                    }
                    ProviderResponse("alternate response")
                },
            )
            val gateway = gateway(
                planner = FixedPlanner(openAi, openRouter),
                invoker = invoker,
                maxRetriesPerDeployment = 1,
            )

            gateway.query.complete(request(), context()).text shouldBe "alternate response"
            invoker.completeCalls.shouldContainExactly("openai-a", "openrouter-a")
        }

        test("authentication failure does not fall back") {
            val invoker = FakeInvoker(
                complete = {
                    throw ProviderException(Vendor.OPENAI, 401, "invalid_api_key", "invalid key")
                },
            )
            val gateway = gateway(FixedPlanner(openAi, openRouter), invoker)

            val error = shouldThrow<GatewayException> {
                gateway.query.complete(request(), context())
            }
            error.error.type shouldBe "provider_authentication_failed"
            error.error.retryable shouldBe false
            invoker.completeCalls.shouldContainExactly("openai-a")
        }

        test("stream failure before first chunk falls back") {
            val invoker = FakeInvoker(
                stream = { deployment ->
                    if (deployment.id == openAi.id) {
                        sequence<ProviderChunk> {
                            throw ProviderException(Vendor.OPENAI, 503, "overloaded", "temporary outage")
                        }
                    } else {
                        sequenceOf(ProviderChunk("fallback chunk"))
                    }
                },
            )
            val gateway = gateway(FixedPlanner(openAi, openRouter), invoker)

            val events = gateway.command.stream(request(stream = true), context()).toList()
            events shouldHaveSize 2
            (events[0] as com.example.llmgateway.domain.model.GatewayEvent.Delta).text shouldBe "fallback chunk"
            (events[0] as com.example.llmgateway.domain.model.GatewayEvent.Delta).model shouldBe "default"
            (events[1] is com.example.llmgateway.domain.model.GatewayEvent.Complete) shouldBe true
            invoker.streamCalls.shouldContainExactly("openai-a", "openrouter-a")
        }

        test("stream failure after first chunk is not retried") {
            val invoker = FakeInvoker(
                stream = {
                    sequence {
                        yield(ProviderChunk("partial"))
                        throw ProviderException(Vendor.OPENAI, 503, "overloaded", "late outage")
                    }
                },
            )
            val gateway = gateway(FixedPlanner(openAi, openRouter), invoker)

            val error = shouldThrow<GatewayException> {
                gateway.command.stream(request(stream = true), context()).toList()
            }
            error.error.type shouldBe "provider_unavailable"
            invoker.streamCalls.shouldContainExactly("openai-a")
        }

        test("complete never exceeds the bounded maximum attempt count") {
            val third = openAi.copy(id = DeploymentId("openai-third"))
            val fourth = openAi.copy(id = DeploymentId("openai-fourth"))
            val planner = object : RoutePlannerPort {
                override fun plan(request: CanonicalChatRequest, context: RequestContext) =
                    RoutingPlan(openAi, listOf(openRouter, third, fourth), 1)
            }
            val invoker = FakeInvoker(
                complete = {
                    throw ProviderException(Vendor.OPENAI, 503, "unavailable", "all providers unavailable")
                },
            )
            val gateway = gateway(planner, invoker)

            shouldThrow<GatewayException> { gateway.query.complete(request(), context()) }
            invoker.completeCalls.shouldContainExactly("openai-a", "openrouter-a", "openai-third")
        }

        test("context window failure is not client retryable after fallback is exhausted") {
            val invoker = FakeInvoker(
                complete = {
                    throw ProviderException(Vendor.OPENAI, 400, "context_length_exceeded", "context window")
                },
            )
            val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, maxAttempts = 2)

            val error = shouldThrow<GatewayException> {
                gateway.query.complete(request(), context())
            }
            error.error.type shouldBe "context_window_exceeded"
            error.error.retryable shouldBe false
        }

        test("stream observes an expired request deadline before calling the provider") {
            val invoker = FakeInvoker()
            val gateway = gateway(FixedPlanner(openAi, openRouter), invoker)
            val expiredContext = RequestContext(
                requestId = RequestId("req-expired"),
                deadline = Instant.now().minusSeconds(1),
            )

            val error = shouldThrow<GatewayException> {
                gateway.command.stream(request(stream = true), expiredContext).toList()
            }
            error.error.type shouldBe "gateway_timeout"
            error.error.code shouldBe "GATEWAY_TIMEOUT"
            invoker.streamCalls shouldHaveSize 0
        }

        test("request admission rejects gateway rate limit before guardrail and provider work") {
            val policy = FailurePolicy()
            val admission = com.example.llmgateway.application.operator.DefaultRequestAdmissionOperator(
                rateLimiter = RateLimiterPort { RateLimitDecision(allowed = false, retryAfterSeconds = 4) },
                guardrail = GuardrailPort { _, _ -> GuardrailDecision.ALLOWED },
                errorFactory = GatewayErrorFactory(policy),
            )

            val error = shouldThrow<GatewayException> {
                admission.execute(request(), context())
            }

            error.error.type shouldBe "gateway_rate_limited"
            error.error.code shouldBe "RATE_LIMITED"
            error.error.retryAfterSeconds shouldBe 4
        }

        test("request admission rejects guardrail decision after rate limit admission") {
            val admission = com.example.llmgateway.application.operator.DefaultRequestAdmissionOperator(
                rateLimiter = RateLimiterPort { RateLimitDecision.ALLOWED },
                guardrail = GuardrailPort { _, _ -> GuardrailDecision(false, "blocked by test") },
                errorFactory = GatewayErrorFactory(FailurePolicy()),
            )

            val error = shouldThrow<GatewayException> {
                admission.execute(request(), context())
            }

            error.error.type shouldBe "guardrail_rejected"
            error.error.message shouldBe "blocked by test"
        }
    }

    private fun gateway(
        planner: RoutePlannerPort,
        invoker: FakeInvoker,
        observer: RecordingObserver = RecordingObserver(),
        maxAttempts: Int = 3,
        maxRetriesPerDeployment: Int = 0,
    ): GatewayInputs {
        val failurePolicy = FailurePolicy()
        val failureClassifier = DefaultFailureClassifier()
        val attemptPolicy = AttemptPolicy(
            failurePolicy = failurePolicy,
            maxTotalAttempts = maxAttempts,
            maxRetriesPerDeployment = maxRetriesPerDeployment,
            maxFallbacks = (maxAttempts - 1).coerceAtLeast(0),
            sleeper = {},
        )
        val errorFactory = GatewayErrorFactory(failurePolicy)
        val deadlineOperator = VirtualThreadDeadlineOperator()

        return GatewayInputs(
            query = DefaultChatCompletionQueryService(
                DefaultCompleteChatOperation(
                    routePlanner = planner,
                    attemptOperator = DefaultCompleteAttemptOperator(
                        providerInvoker = invoker,
                        failureClassifier = failureClassifier,
                        attemptObserver = observer,
                        deadlineOperator = deadlineOperator,
                        circuitBreaker = NoOpCircuitBreaker,
                        failurePolicy = failurePolicy,
                    ),
                    attemptPolicy = attemptPolicy,
                    errorFactory = errorFactory,
                ),
                admissionOperator = NoOpAdmissionOperator,
            ),
            command = DefaultChatCompletionCommandService(
                DefaultStreamChatOperation(
                    routePlanner = planner,
                    attemptOperator = DefaultStreamAttemptOperator(
                        providerInvoker = invoker,
                        failureClassifier = failureClassifier,
                        attemptObserver = observer,
                        deadlineOperator = deadlineOperator,
                        circuitBreaker = NoOpCircuitBreaker,
                        failurePolicy = failurePolicy,
                    ),
                    attemptPolicy = attemptPolicy,
                    errorFactory = errorFactory,
                ),
                admissionOperator = NoOpAdmissionOperator,
            ),
        )
    }

    private fun request(stream: Boolean = false) = CanonicalChatRequest(
        modelGroup = ModelGroup("default"),
        messages = listOf(CanonicalMessage(MessageRole.USER, "hello")),
        stream = stream,
    )

    private fun context() = RequestContext(
        requestId = RequestId("req-test"),
        deadline = Instant.now().plusSeconds(10),
    )
}

private data class GatewayInputs(
    val query: ChatCompletionQueryIn,
    val command: ChatCompletionCommandIn,
)

private class FixedPlanner(
    private val primary: Deployment,
    private val alternate: Deployment,
) : RoutePlannerPort {
    override fun plan(request: CanonicalChatRequest, context: RequestContext) =
        RoutingPlan(primary, listOf(alternate), 1)
}

private class FakeRegistry(
    private val deployments: List<Deployment>,
) : DeploymentRegistryPort {
    override fun snapshot() = RoutingSnapshot(deployments)
}

private class FakeInvoker(
    private val complete: (Deployment) -> ProviderResponse = { ProviderResponse("ok") },
    private val stream: (Deployment) -> Sequence<ProviderChunk> = { sequenceOf(ProviderChunk("ok")) },
) : ProviderInvokerPort {
    val completeCalls = mutableListOf<String>()
    val streamCalls = mutableListOf<String>()

    override fun complete(
        deployment: Deployment,
        request: CanonicalChatRequest,
        attempt: AttemptContext,
    ): ProviderResponse {
        completeCalls += deployment.id.value
        return complete(deployment)
    }

    override fun stream(
        deployment: Deployment,
        request: CanonicalChatRequest,
        attempt: AttemptContext,
    ): Sequence<ProviderChunk> {
        streamCalls += deployment.id.value
        return stream(deployment)
    }
}

private object NoOpCircuitBreaker : CircuitBreakerPort

private object NoOpAdmissionOperator : RequestAdmissionOperator {
    override fun execute(request: CanonicalChatRequest, context: RequestContext) = Unit
}

private class RecordingObserver : AttemptObserverPort {
    val outcomes = mutableListOf<AttemptOutcome>()

    override fun onStart(context: AttemptContext) = Unit

    override fun onStop(context: AttemptContext, outcome: AttemptOutcome) {
        outcomes += outcome
    }
}
