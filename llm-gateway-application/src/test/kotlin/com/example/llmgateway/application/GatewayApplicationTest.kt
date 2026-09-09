package com.example.llmgateway.application

import com.example.llmgateway.application.operation.DefaultCompleteChatOperation
import com.example.llmgateway.application.operation.DefaultStreamChatOperation
import com.example.llmgateway.application.operator.DefaultCompleteAttemptOperator
import com.example.llmgateway.application.operator.DefaultOutputGuardrailOperator
import com.example.llmgateway.application.operator.DefaultStreamAttemptOperator
import com.example.llmgateway.application.operator.NoOpOutputGuardrailOperator
import com.example.llmgateway.application.operator.OutputGuardrailOperator
import com.example.llmgateway.application.operator.RequestAdmissionOperator
import com.example.llmgateway.application.operator.VirtualThreadDeadlineOperator
import com.example.llmgateway.application.policy.AttemptPolicy
import com.example.llmgateway.application.policy.DefaultFailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.port.`in`.CompleteChatCommandIn
import com.example.llmgateway.application.port.`in`.StreamChatCommandIn
import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.DeploymentAvailabilityPort
import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.InputGuardrailPort
import com.example.llmgateway.application.port.out.OutputGuardrailPort
import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.application.port.out.RateLimiterPort
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.application.port.out.RoutingSnapshotPort
import com.example.llmgateway.application.service.DefaultCompleteChatCommandService
import com.example.llmgateway.application.service.DefaultStreamChatCommandService
import com.example.llmgateway.application.service.WeightedRendezvousRoutePlanner
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ExecutionId
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.error.ProviderException
import com.example.llmgateway.domain.error.ProviderFailurePhase
import com.example.llmgateway.domain.error.RequestDisposition
import com.example.llmgateway.domain.execution.AttemptCancelledWithUsage
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptFailure
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.CanonicalMessage
import com.example.llmgateway.domain.inference.chat.GatewayCompleteEvent
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.inference.chat.GatewayDeltaEvent
import com.example.llmgateway.domain.inference.chat.ProviderChunk
import com.example.llmgateway.domain.inference.chat.ProviderResponse
import com.example.llmgateway.domain.observation.AttemptObservationHandle
import com.example.llmgateway.domain.policy.GuardrailDecision
import com.example.llmgateway.domain.policy.RateLimitDecision
import com.example.llmgateway.domain.routing.CircuitPermit
import com.example.llmgateway.domain.routing.Deployment
import com.example.llmgateway.domain.routing.RoutingPlan
import com.example.llmgateway.domain.routing.RoutingSnapshot
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
        for (streaming in listOf(false, true)) {
            test("admission failure after a provider attempt cannot invite whole-request regeneration streaming=$streaming") {
                val stored = TestAttemptJournal()
                val journal = object : com.example.llmgateway.application.port.out.AttemptJournalPort by stored {
                    override fun prepare(context: AttemptContext, pricing: com.example.llmgateway.domain.accounting.PricingSnapshot?) {
                        if (context.sequence > 1) error("Injected fallback preparation failure")
                        stored.prepare(context, pricing)
                    }
                }
                fun unavailable(): Nothing = throw ProviderException(vendor = Vendor.OPENAI, statusCode = 503, message = "fixture failure")
                val invoker = FakeInvoker(complete = { unavailable() }, stream = { unavailable() })
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, journal = journal)
                val error = shouldThrow<GatewayException> {
                    if (streaming) gateway.command.stream(request(true), requestContext()).use { it.toList() }
                    else gateway.completeCommand.complete(request(), requestContext())
                }
                error.error.code shouldBe "ADMISSION_UNAVAILABLE"
                error.error.retryable shouldBe false
                invoker.completeCalls.size + invoker.streamCalls.size shouldBe 1
            }

            test("budget rejection stays a business denial and never calls a provider streaming=$streaming") {
                val journal = object : com.example.llmgateway.application.port.out.AttemptJournalPort by TestAttemptJournal() {
                    override fun prepare(context: AttemptContext, pricing: com.example.llmgateway.domain.accounting.PricingSnapshot?) {
                        throw GatewayException(com.example.llmgateway.domain.error.GatewayError(
                            type = "budget_exceeded", code = "BUDGET_EXCEEDED",
                            category = com.example.llmgateway.domain.error.ErrorCategory.TRANSIENT,
                            message = "Insufficient budget", retryable = false, requestId = context.requestId,
                        ))
                    }
                }
                val invoker = FakeInvoker()
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, journal = journal)
                val error = shouldThrow<GatewayException> {
                    if (streaming) gateway.command.stream(request(true), requestContext()).use { it.toList() }
                    else gateway.completeCommand.complete(request(), requestContext())
                }
                error.error.code shouldBe "BUDGET_EXCEEDED"
                error.error.retryable shouldBe false
                invoker.completeCalls.size + invoker.streamCalls.size shouldBe 0
            }

            test("every attempt retains request budget time and explicit output limit streaming=$streaming") {
                val observer = RecordingObserver()
                val invoker = FakeInvoker()
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, observer)
                val context = requestContext().copy(startedAt = Instant.parse("2026-08-31T23:59:59Z"))
                val bounded = request(streaming).copy(options = com.example.llmgateway.domain.inference.chat.CanonicalOptions(maxCompletionTokens = 17))
                if (streaming) gateway.command.stream(bounded, context).use { it.toList() }
                else gateway.completeCommand.complete(bounded, context)
                observer.attempts.single().budgetAt shouldBe context.startedAt
                observer.attempts.single().requestedOutputTokens shouldBe 17
            }

            for (stage in listOf("prepare", "dispatch")) {
                test("journal $stage failure blocks provider and fallback streaming=$streaming") {
                    val journal = TestAttemptJournal().apply { failureAt = stage }
                    var ignored = 0
                    val circuit = object : CircuitBreakerPort {
                        override fun onIgnored(deployment: Deployment, permit: CircuitPermit) { ignored++ }
                    }
                    val invoker = FakeInvoker()
                    val gateway = gateway(FixedPlanner(openAi, openRouter), invoker,
                        journal = journal, circuitBreaker = circuit)
                    val error = shouldThrow<GatewayException> {
                        if (streaming) gateway.command.stream(request(true), requestContext()).use { it.toList() }
                        else gateway.completeCommand.complete(request(), requestContext())
                    }
                    error.error.code shouldBe "ADMISSION_UNAVAILABLE"
                    error.error.retryable shouldBe true
                    invoker.completeCalls.size + invoker.streamCalls.size shouldBe 0
                    journal.events shouldBe if (stage == "prepare") listOf("prepare") else listOf("prepare", "dispatch")
                    journal.states.values.toList() shouldBe if (stage == "prepare") emptyList() else listOf("PREPARED")
                    ignored shouldBe if (stage == "dispatch") 1 else 0
                }
            }

            test("lost dispatch acknowledgement never calls provider or abandons committed intent streaming=$streaming") {
                val journal = TestAttemptJournal()
                val ambiguous = object : com.example.llmgateway.application.port.out.AttemptJournalPort by journal {
                    override fun dispatch(context: AttemptContext, permit: CircuitPermit) {
                        journal.dispatch(context, permit)
                        error("Injected acknowledgement loss after commit")
                    }
                }
                val invoker = FakeInvoker()
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, journal = ambiguous)
                val error = shouldThrow<GatewayException> {
                    if (streaming) gateway.command.stream(request(true), requestContext()).use { it.toList() }
                    else gateway.completeCommand.complete(request(), requestContext())
                }
                error.error.code shouldBe "ADMISSION_UNAVAILABLE"
                journal.events shouldBe listOf("prepare", "dispatch")
                journal.states.values.toList() shouldBe listOf("DISPATCH_INTENT")
                invoker.completeCalls.size + invoker.streamCalls.size shouldBe 0
            }

            test("interrupted circuit acquisition preserves interruption and abandons preparation streaming=$streaming") {
                val journal = TestAttemptJournal()
                val circuit = object : CircuitBreakerPort {
                    override fun acquire(deployment: Deployment, owner: AttemptId): CircuitPermit? =
                        throw InterruptedException("fixture cancellation")
                }
                val invoker = FakeInvoker()
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker,
                    journal = journal, circuitBreaker = circuit)
                try {
                    shouldThrow<InterruptedException> {
                        if (streaming) gateway.command.stream(request(true), requestContext()).use { it.toList() }
                        else gateway.completeCommand.complete(request(), requestContext())
                    }
                    Thread.currentThread().isInterrupted shouldBe true
                } finally {
                    Thread.interrupted()
                }
                journal.states.values.toList() shouldBe listOf("ABANDONED")
                invoker.completeCalls.size + invoker.streamCalls.size shouldBe 0
            }

            test("provider is invoked after dispatch intent and recorded before success streaming=$streaming") {
                val journal = TestAttemptJournal()
                fun providerEntered() {
                    journal.states.values.toList() shouldBe listOf("DISPATCH_INTENT")
                    journal.events += "provider"
                }
                val invoker = FakeInvoker(
                    complete = { providerEntered(); ProviderResponse("ok") },
                    stream = { providerEntered(); sequenceOf(ProviderChunk("ok")) },
                )
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, journal = journal)
                if (streaming) gateway.command.stream(request(true), requestContext()).use { it.toList() }
                else gateway.completeCommand.complete(request(), requestContext())
                journal.events shouldBe listOf("prepare", "dispatch", "provider", "record")
                journal.states.values.toList() shouldBe listOf("RECORDED")
                invoker.completeCalls.size + invoker.streamCalls.size shouldBe 1
            }

            test("terminal journal failure retains intent and never regenerates streaming=$streaming") {
                val journal = TestAttemptJournal().apply { failureAt = "record" }
                val invoker = FakeInvoker()
                val events = mutableListOf<GatewayEvent>()
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, journal = journal)
                val error = shouldThrow<GatewayException> {
                    if (streaming) gateway.command.stream(request(true), requestContext()).use { stream ->
                        stream.forEach { events += it }
                    } else gateway.completeCommand.complete(request(), requestContext())
                }
                error.error.code shouldBe "OUTCOME_UNKNOWN"
                error.error.retryable shouldBe false
                events.filterIsInstance<GatewayCompleteEvent>().size shouldBe 0
                journal.events shouldBe listOf("prepare", "dispatch", "record")
                journal.states.values.toList() shouldBe listOf("DISPATCH_INTENT")
                invoker.completeCalls.size + invoker.streamCalls.size shouldBe 1
            }

            test("circuit denial abandons only the prepared attempt streaming=$streaming") {
                val journal = TestAttemptJournal()
                val circuit = object : CircuitBreakerPort {
                    override fun acquire(deployment: Deployment, owner: AttemptId): CircuitPermit? = null
                }
                val invoker = FakeInvoker()
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker,
                    journal = journal, circuitBreaker = circuit)
                shouldThrow<GatewayException> {
                    if (streaming) gateway.command.stream(request(true), requestContext()).use { it.toList() }
                    else gateway.completeCommand.complete(request(), requestContext())
                }
                journal.events shouldBe listOf("prepare", "abandon")
                journal.states.values.toList() shouldBe listOf("ABANDONED")
                invoker.completeCalls.size + invoker.streamCalls.size shouldBe 0
            }

            test("failed predispatch compensation retains prepared evidence streaming=$streaming") {
                val journal = TestAttemptJournal().apply { failureAt = "abandon" }
                val circuit = object : CircuitBreakerPort {
                    override fun acquire(deployment: Deployment, owner: AttemptId): CircuitPermit? = null
                }
                val invoker = FakeInvoker()
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker,
                    journal = journal, circuitBreaker = circuit)
                val error = shouldThrow<GatewayException> {
                    if (streaming) gateway.command.stream(request(true), requestContext()).use { it.toList() }
                    else gateway.completeCommand.complete(request(), requestContext())
                }
                error.error.code shouldBe "ADMISSION_UNAVAILABLE"
                journal.states.values.toList() shouldBe listOf("PREPARED")
                invoker.completeCalls.size + invoker.streamCalls.size shouldBe 0
            }
        }

        test("early stream termination closes provider once and records cancellation rather than success") {
            var closes = 0
            val observer = RecordingObserver()
            val invoker = FakeInvoker(
                stream = { sequenceOf(ProviderChunk("first"), ProviderChunk("second")) },
                onStreamClose = { closes++ },
            )
            val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, observer)
            val stream = gateway.command.stream(request(true), requestContext())
            stream.use { it.take(1).toList().size shouldBe 1 }
            stream.close()
            closes shouldBe 1
            (observer.outcomes.single() is AttemptCancelledWithUsage) shouldBe true
            invoker.streamCalls shouldBe listOf(openAi.id.value)
        }

        test("JSON and SSE preserve execution identity and initial retry fallback transitions") {
            for (streaming in listOf(false, true)) {
                val observer = RecordingObserver()
                fun failPrimary(candidate: Deployment) {
                    if (candidate.id == openAi.id) throw ProviderException(
                        vendor = Vendor.OPENAI,
                        statusCode = 503,
                        providerCode = "connection_failed",
                        message = "connection failed",
                        phase = ProviderFailurePhase.CONNECT,
                        requestDisposition = RequestDisposition.NOT_SENT,
                    )
                }
                val invoker = FakeInvoker(
                    complete = { candidate -> failPrimary(candidate); ProviderResponse("ok") },
                    stream = { candidate -> failPrimary(candidate); sequenceOf(ProviderChunk("ok")) },
                )
                val gateway = gateway(
                    FixedPlanner(openAi, openRouter), invoker, observer,
                    maxAttempts = 4, maxRetriesPerDeployment = 2,
                )
                val context = RequestContext(RequestId("same-correlation"))
                if (streaming) gateway.command.stream(request(true), context).toList()
                else gateway.completeCommand.complete(request(), context)
                observer.attempts.map { it.kind } shouldBe listOf(
                    AttemptKind.INITIAL, AttemptKind.RETRY, AttemptKind.RETRY, AttemptKind.FALLBACK,
                )
                observer.attempts.map { it.executionId }.toSet() shouldBe setOf(context.executionId)
                observer.attempts.map { it.requestId }.toSet() shouldBe setOf(context.requestId)
                observer.attempts.map { it.attemptId }.toSet().size shouldBe 4
                observer.attempts.map { it.sequence } shouldBe listOf(1, 2, 3, 4)
                observer.attempts.map { it.deployment.id } shouldBe listOf(openAi.id, openAi.id, openAi.id, openRouter.id)
            }
        }

        test("stream deadline closes the blocked provider handle without duplicate terminal accounting") {
            val closed = java.util.concurrent.CountDownLatch(1)
            val closes = java.util.concurrent.atomic.AtomicInteger()
            val observer = RecordingObserver()
            val invoker = FakeInvoker(
                stream = { sequence { Thread.sleep(5000); yield(ProviderChunk("late")) } },
                onStreamClose = { closes.incrementAndGet(); closed.countDown() },
            )
            val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, observer, maxAttempts = 1)
            val context = requestContext().copy(deadline = Instant.now().plusSeconds(1))
            val error = shouldThrow<GatewayException> {
                gateway.command.stream(request(true), context).use { it.toList() }
            }
            error.error.code shouldBe "GATEWAY_TIMEOUT"
            closed.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
            closes.get() shouldBe 1
            observer.outcomes.size shouldBe 1
        }

        test("route planner excludes disabled, incompatible, and other-group deployments") {
            val disabled = openAi.copy(id = DeploymentId("disabled"), enabled = false)
            val nonStreaming = openRouter.copy(id = DeploymentId("non-streaming"), supportsStreaming = false)
            val otherGroup = openRouter.copy(id = DeploymentId("other-group"), modelGroup = ModelGroup("other"))
            val planner = WeightedRendezvousRoutePlanner(
                FakeRegistry(listOf(disabled, nonStreaming, otherGroup, openAi)),
                NoOpCircuitBreaker,
            )

            planner.plan(request(stream = true), requestContext()).candidates.map { it.id.value }
                .shouldContainExactly("openai-a")
        }

        test("routing is deterministic for the same execution across gateway instances") {
            val planner = WeightedRendezvousRoutePlanner(
                FakeRegistry(listOf(openAi, openRouter)),
                NoOpCircuitBreaker,
            )
            val secondPlanner = WeightedRendezvousRoutePlanner(
                FakeRegistry(listOf(openAi, openRouter)),
                NoOpCircuitBreaker,
            )

            val selected = planner.plan(request(), requestContext("stable-request")).primary.id
            secondPlanner.plan(request(), requestContext("stable-request")).primary.id shouldBe selected
        }

        test("weighted routing keeps alternates unique while distributing requests") {
            val weightedOpenAi = openAi.copy(weight = 2)
            val planner = WeightedRendezvousRoutePlanner(
                FakeRegistry(listOf(weightedOpenAi, openRouter)),
                NoOpCircuitBreaker,
            )
            val primaries = (1..100).map { planner.plan(request(), requestContext("weighted-$it")).primary.id.value }

            primaries.toSet() shouldBe setOf("openai-a", "openrouter-a")
            (primaries.count { it == "openai-a" } in 55..80) shouldBe true
            planner.plan(request(), requestContext("weighted-candidates")).candidates.map { it.id.value }
                .toSet() shouldBe setOf("openai-a", "openrouter-a")
        }

        test("planner excludes a previous deployment during re-evaluation") {
            val planner = WeightedRendezvousRoutePlanner(
                FakeRegistry(listOf(openAi, openRouter)),
                NoOpCircuitBreaker,
            )

            planner.plan(request(), requestContext(), setOf(openAi.id)).candidates.map { it.id.value }
                .shouldContainExactly("openrouter-a")
        }

        test("priority tier is exhausted before a lower-priority deployment is selected") {
            val preferred = openAi.copy(priority = 0)
            val peer = openRouter.copy(id = DeploymentId("openrouter-peer"), priority = 0, weight = 100)
            val fallback = openRouter.copy(priority = 1, weight = 100)
            val planner = WeightedRendezvousRoutePlanner(
                FakeRegistry(listOf(preferred, peer, fallback)),
                NoOpCircuitBreaker,
            )

            val plan = planner.plan(request(), requestContext("priority-primary"))
            plan.primary.priority shouldBe 0
            plan.alternates.map { it.priority } shouldBe listOf(0, 1)
            planner.plan(
                request(),
                requestContext("priority-fallback"),
                setOf(preferred.id, peer.id),
            ).primary.id shouldBe fallback.id
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

            gateway.completeCommand.complete(request(), requestContext()).text shouldBe "fallback response"
            invoker.completeCalls.shouldContainExactly("openai-a", "openrouter-a")
            observer.outcomes shouldHaveSize 2
            (observer.outcomes[0] is AttemptFailure) shouldBe true
            (observer.outcomes[1] is AttemptSuccess) shouldBe true
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

            gateway.completeCommand.complete(request(), requestContext()).text shouldBe "rate limit fallback"
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

            gateway.completeCommand.complete(request(), requestContext()).text shouldBe "recovered"
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

            gateway.completeCommand.complete(request(), requestContext()).text shouldBe "alternate response"
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
                gateway.completeCommand.complete(request(), requestContext())
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

            val events = gateway.command.stream(request(stream = true), requestContext()).toList()
            events shouldHaveSize 2
            (events[0] as com.example.llmgateway.domain.inference.chat.GatewayDeltaEvent).text shouldBe "fallback chunk"
            (events[0] as com.example.llmgateway.domain.inference.chat.GatewayDeltaEvent).model shouldBe "default"
            (events[1] is com.example.llmgateway.domain.inference.chat.GatewayCompleteEvent) shouldBe true
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
                gateway.command.stream(request(stream = true), requestContext()).toList()
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

            shouldThrow<GatewayException> { gateway.completeCommand.complete(request(), requestContext()) }
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
                gateway.completeCommand.complete(request(), requestContext())
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
                rateLimiter = RateLimiterPort { _, _ -> RateLimitDecision(allowed = false, retryAfterSeconds = 4) },
                guardrail = InputGuardrailPort { _, _ -> GuardrailDecision.ALLOWED },
                errorFactory = GatewayErrorFactory(policy),
            )

            val error = shouldThrow<GatewayException> {
                admission.execute(request(), requestContext())
            }

            error.error.type shouldBe "gateway_rate_limited"
            error.error.code shouldBe "RATE_LIMITED"
            error.error.retryAfterSeconds shouldBe 4
        }

        test("request admission exposes a rate-limit backend outage as retryable service unavailability") {
            val policy = FailurePolicy()
            val admission = com.example.llmgateway.application.operator.DefaultRequestAdmissionOperator(
                rateLimiter = RateLimiterPort { _, _ ->
                    RateLimitDecision(allowed = false, retryAfterSeconds = 1, backendAvailable = false)
                },
                guardrail = InputGuardrailPort { _, _ -> GuardrailDecision.ALLOWED },
                errorFactory = GatewayErrorFactory(policy),
            )

            val error = shouldThrow<GatewayException> {
                admission.execute(request(), requestContext())
            }

            error.error.type shouldBe "rate_limit_unavailable"
            error.error.code shouldBe "RATE_LIMIT_BACKEND_UNAVAILABLE"
            error.error.retryable shouldBe true
        }

        test("request admission rejects guardrail decision after rate limit admission") {
            val admission = com.example.llmgateway.application.operator.DefaultRequestAdmissionOperator(
                rateLimiter = RateLimiterPort { _, _ -> RateLimitDecision.ALLOWED },
                guardrail = InputGuardrailPort { _, _ -> GuardrailDecision(false, "blocked by test") },
                errorFactory = GatewayErrorFactory(FailurePolicy()),
            )

            val error = shouldThrow<GatewayException> {
                admission.execute(request(), requestContext())
            }

            error.error.type shouldBe "guardrail_rejected"
            error.error.message shouldBe "The request was rejected by a gateway policy"
        }

        test("complete output guardrail blocks a provider response before it reaches the client") {
            val gateway = gateway(
                planner = FixedPlanner(openAi, openRouter),
                invoker = FakeInvoker(complete = { ProviderResponse("blocked response") }),
                outputGuardrailOperator = outputGuardrail("blocked"),
            )

            val error = shouldThrow<GatewayException> {
                gateway.completeCommand.complete(request(), requestContext())
            }

            error.error.type shouldBe "response_guardrail_rejected"
            error.error.code shouldBe "OUTPUT_POLICY_BLOCKED"
        }

        test("stream output guardrail blocks a phrase split across provider chunks") {
            val gateway = gateway(
                planner = FixedPlanner(openAi, openRouter),
                invoker = FakeInvoker(stream = {
                    sequenceOf(ProviderChunk("prefix bloc"), ProviderChunk("ked suffix"))
                }),
                outputGuardrailOperator = outputGuardrail("blocked"),
            )

            val error = shouldThrow<GatewayException> {
                gateway.command.stream(request(stream = true), requestContext()).toList()
            }

            error.error.type shouldBe "response_guardrail_rejected"
            error.error.code shouldBe "OUTPUT_POLICY_BLOCKED"
        }
    }

    init {
        test("routing inspects all eligible candidates without acquiring probes") {
            val inspected = mutableListOf<Deployment>()
            val circuit = object : CircuitBreakerPort {
                override fun inspect(deployment: Deployment): Boolean {
                    inspected += deployment
                    return true
                }
                override fun acquire(deployment: Deployment, owner: AttemptId): CircuitPermit? =
                    error("Planning must not acquire a probe")
            }
            val planner = WeightedRendezvousRoutePlanner(FakeRegistry(listOf(openAi, openRouter)), circuit)
            planner.plan(request(), requestContext()).candidates.size shouldBe 2
            inspected.toSet() shouldBe setOf(openAi, openRouter)
        }

        test("JSON and streaming attempts finish only their selected circuit permit") {
            for (streaming in listOf(false, true)) {
                val acquired = mutableListOf<Pair<Deployment, CircuitPermit>>()
                val completed = mutableListOf<Pair<Deployment, CircuitPermit>>()
                val circuit = object : CircuitBreakerPort {
                    override fun acquire(deployment: Deployment, owner: AttemptId): CircuitPermit {
                        val permit = CircuitPermit(owner, "generation")
                        acquired += deployment to permit
                        return permit
                    }
                    override fun onSuccess(deployment: Deployment, permit: CircuitPermit) {
                        completed += deployment to permit
                    }
                }
                val gateway = gateway(FixedPlanner(openAi, openRouter), FakeInvoker(), circuitBreaker = circuit)
                if (streaming) gateway.command.stream(request(true), requestContext()).toList()
                else gateway.completeCommand.complete(request(), requestContext())
                acquired.size shouldBe 1
                acquired.single().first shouldBe openAi
                completed shouldBe acquired
            }
        }

        test("circuit contention never invokes a provider or records a provider failure") {
            for (streaming in listOf(false, true)) {
                val invoker = FakeInvoker()
                val observer = RecordingObserver()
                val circuit = object : CircuitBreakerPort {
                    override fun acquire(deployment: Deployment, owner: AttemptId): CircuitPermit? = null
                }
                val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, observer, circuitBreaker = circuit)
                val error = shouldThrow<GatewayException> {
                    if (streaming) gateway.command.stream(request(true), requestContext()).toList()
                    else gateway.completeCommand.complete(request(), requestContext())
                }
                error.error.code shouldBe "LLM_UNAVAILABLE"
                invoker.completeCalls.size shouldBe 0
                invoker.streamCalls.size shouldBe 0
                observer.outcomes.size shouldBe 0
            }
        }
    }

    init {
        test("JSON and SSE retain route and price snapshots across retry and fallback") {
            for (streaming in listOf(false, true)) {
                val first = openAi.copy(priority = 0)
                val alternate = openRouter.copy(priority = 10)
                val price = com.example.llmgateway.domain.accounting.PricingSnapshot(
                    "price-v1", "0.001".toBigDecimal(), "0.002".toBigDecimal(),
                )
                val initial = RoutingSnapshot(listOf(first, alternate), 1,
                    mapOf(first.id to price, alternate.id to price))
                val replacement = RoutingSnapshot(
                    listOf(first.copy(priority = 20), alternate.copy(priority = 0, model = "replacement")),
                    2, mapOf(first.id to com.example.llmgateway.domain.accounting.PricingSnapshot(
                        "price-v2", "1".toBigDecimal(), price.outputCostPerTokenUsd),
                        alternate.id to com.example.llmgateway.domain.accounting.PricingSnapshot(
                            "price-v2", "1".toBigDecimal(), price.outputCostPerTokenUsd)),
                )
                val authority = java.util.concurrent.atomic.AtomicReference(initial)
                var reads = 0
                val planner = WeightedRendezvousRoutePlanner(RoutingSnapshotPort {
                    reads++
                    authority.get()
                }, NoOpCircuitBreaker)
                val observer = RecordingObserver()
                val usage = com.example.llmgateway.domain.accounting.Usage(inputTokens = 10, outputTokens = 5)
                fun dispatch(candidate: Deployment) {
                    if (candidate.id == first.id) {
                        authority.set(replacement)
                        throw ProviderException(Vendor.OPENAI, 503, "connect", "not sent",
                            phase = ProviderFailurePhase.CONNECT, requestDisposition = RequestDisposition.NOT_SENT)
                    }
                }
                val invoker = FakeInvoker(
                    complete = { dispatch(it); ProviderResponse("ok", usage = usage) },
                    stream = { dispatch(it); sequenceOf(ProviderChunk("ok", usage = usage)) },
                )
                val gateway = gateway(planner, invoker, observer, maxRetriesPerDeployment = 1)
                if (streaming) gateway.command.stream(request(true), requestContext()).toList()
                else gateway.completeCommand.complete(request(), requestContext())

                reads shouldBe 1
                observer.attempts.map { it.kind } shouldBe listOf(AttemptKind.INITIAL, AttemptKind.RETRY, AttemptKind.FALLBACK)
                observer.attempts.last().deployment shouldBe alternate
                val cost = (observer.outcomes.last() as AttemptSuccess).cost
                cost.pricingVersion shouldBe "price-v1"
                cost.usd shouldBe "0.020000000000000000".toBigDecimal()

                // The next execution adopts the published view; the previous one was not pinned globally.
                if (streaming) gateway.command.stream(request(true), requestContext()).toList()
                else gateway.completeCommand.complete(request(), requestContext())
                reads shouldBe 2
                observer.attempts.last().deployment.model shouldBe "replacement"
                (observer.outcomes.last() as AttemptSuccess).cost.pricingVersion shouldBe "price-v2"
            }
        }

        test("emergency disable or availability failure prevents retry without provider failure accounting") {
            for (streaming in listOf(false, true)) {
                for (lookupFails in listOf(false, true)) {
                    var disabled = false
                    val observer = RecordingObserver()
                    fun dispatch(): Nothing {
                        disabled = true
                        throw ProviderException(Vendor.OPENAI, 503, "connect", "not sent",
                            phase = ProviderFailurePhase.CONNECT, requestDisposition = RequestDisposition.NOT_SENT)
                    }
                    val invoker = FakeInvoker(complete = { dispatch() }, stream = { dispatch() })
                    val gateway = gateway(FixedPlanner(openAi, openRouter), invoker, observer,
                        maxRetriesPerDeployment = 1,
                        deploymentAvailability = DeploymentAvailabilityPort {
                            if (disabled && lookupFails) error("availability store unavailable")
                            !disabled
                        })
                    val error = shouldThrow<GatewayException> {
                        if (streaming) gateway.command.stream(request(true), requestContext()).toList()
                        else gateway.completeCommand.complete(request(), requestContext())
                    }
                    error.error.code shouldBe "LLM_UNAVAILABLE"
                    (invoker.completeCalls + invoker.streamCalls) shouldBe listOf(openAi.id.value)
                    observer.attempts.size shouldBe 1
                    observer.outcomes.size shouldBe 1
                }
            }
        }

        test("late streaming failure accounts partial usage with the captured price") {
            val price = com.example.llmgateway.domain.accounting.PricingSnapshot("v1", "0.001".toBigDecimal(), "0.002".toBigDecimal())
            val authority = java.util.concurrent.atomic.AtomicReference(
                RoutingSnapshot(listOf(openAi), 1, mapOf(openAi.id to price)))
            val planner = WeightedRendezvousRoutePlanner(RoutingSnapshotPort { authority.get() }, NoOpCircuitBreaker)
            val observer = RecordingObserver()
            val invoker = FakeInvoker(stream = {
                sequence {
                    yield(ProviderChunk("partial", usage = com.example.llmgateway.domain.accounting.Usage(inputTokens = 10)))
                    authority.set(RoutingSnapshot(listOf(openAi), 2,
                        mapOf(openAi.id to com.example.llmgateway.domain.accounting.PricingSnapshot(
                            "v2", "1".toBigDecimal(), price.outputCostPerTokenUsd))))
                    throw ProviderException(Vendor.OPENAI, 503, "late failure", "interrupted upstream")
                }
            })
            val gateway = gateway(planner, invoker, observer)
            shouldThrow<GatewayException> { gateway.command.stream(request(true), requestContext()).toList() }
            invoker.streamCalls shouldBe listOf(openAi.id.value)
            val cost = (observer.outcomes.single() as AttemptFailure).cost
            cost.pricingVersion shouldBe "v1"
            cost.usd shouldBe "0.010000000000000000".toBigDecimal()
        }

        test("snapshot acquisition failure fails closed before provider dispatch") {
            for (streaming in listOf(false, true)) {
                val invoker = FakeInvoker()
                val planner = WeightedRendezvousRoutePlanner(
                    RoutingSnapshotPort { error("inconsistent authority unavailable") }, NoOpCircuitBreaker)
                val gateway = gateway(planner, invoker)
                shouldThrow<GatewayException> {
                    if (streaming) gateway.command.stream(request(true), requestContext()).toList()
                    else gateway.completeCommand.complete(request(), requestContext())
                }
                invoker.completeCalls.size + invoker.streamCalls.size shouldBe 0
            }
        }

        test("attempt accounting failure stops successful or failed providers without retry fallback or success terminal") {
            listOf(false, true).forEach { streaming ->
                listOf(false, true).forEach { providerFails ->
                    val writes = java.util.concurrent.atomic.AtomicInteger()
                    val closes = java.util.concurrent.atomic.AtomicInteger()
                    val observer = RecordingObserver()
                    val invoker = FakeInvoker(
                        complete = {
                            if (providerFails) throw ProviderException(Vendor.OPENAI, statusCode = 503, message = "provider detail")
                            ProviderResponse("ok")
                        },
                        stream = { sequence {
                            if (providerFails) throw ProviderException(Vendor.OPENAI, statusCode = 503, message = "provider detail")
                            yield(ProviderChunk("partial"))
                        } },
                        onStreamClose = { closes.incrementAndGet() },
                    )
                    val gateway = gateway(
                        FixedPlanner(openAi, openRouter), invoker, observer,
                        maxRetriesPerDeployment = 2,
                        attemptAccounting = object : com.example.llmgateway.application.port.out.AttemptAccountingPort {
                            override fun record(context: AttemptContext, outcome: AttemptOutcome) {
                                writes.incrementAndGet()
                                error("private database detail")
                            }
                        },
                    )
                    val emitted = mutableListOf<GatewayEvent>()
                    val failure = shouldThrow<GatewayException> {
                        if (streaming) gateway.command.stream(request(true), requestContext()).use { stream ->
                            stream.forEach(emitted::add)
                        } else gateway.completeCommand.complete(request(), requestContext())
                    }
                    failure.error.code shouldBe "OUTCOME_UNKNOWN"
                    failure.error.retryable shouldBe false
                    failure.error.retryAfterSeconds shouldBe null
                    failure.cause?.message shouldBe "private database detail"
                    writes.get() shouldBe 1
                    invoker.completeCalls.size + invoker.streamCalls.size shouldBe 1
                    observer.outcomes.size shouldBe 1
                    emitted.none { it is GatewayCompleteEvent } shouldBe true
                    closes.get() shouldBe if (streaming) 1 else 0
                }
            }
        }

        test("snapshots and plans defensively freeze collections") {
            val candidates = mutableListOf(openAi)
            val pricing = mutableMapOf(openAi.id to com.example.llmgateway.domain.accounting.PricingSnapshot("v1", null, null))
            val snapshot = RoutingSnapshot(candidates, 1, pricing)
            val plan = RoutingPlan(openRouter, candidates, snapshot.version, pricing)
            candidates.clear()
            pricing.clear()
            snapshot.deployments shouldBe listOf(openAi)
            snapshot.pricing.size shouldBe 1
            plan.alternates shouldBe listOf(openAi)
            plan.pricing.size shouldBe 1
            shouldThrow<UnsupportedOperationException> { (snapshot.deployments as MutableList<Deployment>).clear() }
            shouldThrow<UnsupportedOperationException> {
                (plan.pricing as MutableMap<DeploymentId, com.example.llmgateway.domain.accounting.PricingSnapshot>).clear()
            }
        }
    }

    private fun gateway(
        planner: RoutePlannerPort,
        invoker: FakeInvoker,
        observer: RecordingObserver = RecordingObserver(),
        maxAttempts: Int = 3,
        maxRetriesPerDeployment: Int = 0,
        outputGuardrailOperator: OutputGuardrailOperator = NoOpOutputGuardrailOperator,
        circuitBreaker: CircuitBreakerPort = NoOpCircuitBreaker,
        deploymentAvailability: DeploymentAvailabilityPort = DeploymentAvailabilityPort { true },
        attemptAccounting: com.example.llmgateway.application.port.out.AttemptAccountingPort =
            com.example.llmgateway.application.port.out.NoOpAttemptAccountingPort,
        journal: com.example.llmgateway.application.port.out.AttemptJournalPort = TestAttemptJournal(attemptAccounting),
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
            completeCommand = DefaultCompleteChatCommandService(
                DefaultCompleteChatOperation(
                    routePlanner = planner,
                    attemptOperator = DefaultCompleteAttemptOperator(
                        providerInvoker = invoker,
                        failureClassifier = failureClassifier,
                        attemptObserver = observer,
                        deadlineOperator = deadlineOperator,
                        circuitBreaker = circuitBreaker,
                        failurePolicy = failurePolicy,
                        deploymentAvailability = deploymentAvailability,
                        outputGuardrailOperator = outputGuardrailOperator,
                        attemptAccounting = journal,
                    ),
                    attemptPolicy = attemptPolicy,
                    errorFactory = errorFactory,
                ),
                admissionOperator = NoOpAdmissionOperator,
            ),
            command = DefaultStreamChatCommandService(
                DefaultStreamChatOperation(
                    routePlanner = planner,
                    attemptOperator = DefaultStreamAttemptOperator(
                        providerInvoker = invoker,
                        failureClassifier = failureClassifier,
                        attemptObserver = observer,
                        deadlineOperator = deadlineOperator,
                        circuitBreaker = circuitBreaker,
                        failurePolicy = failurePolicy,
                        deploymentAvailability = deploymentAvailability,
                        outputGuardrailOperator = outputGuardrailOperator,
                        attemptAccounting = journal,
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

    private fun requestContext(requestId: String = "req-test") = RequestContext(
        requestId = RequestId(requestId),
        executionId = ExecutionId(requestId),
        deadline = Instant.now().plusSeconds(10),
    )

    private fun outputGuardrail(blockedPhrase: String): OutputGuardrailOperator = DefaultOutputGuardrailOperator(
        guardrail = OutputGuardrailPort { output, _ ->
            if (output.contains(blockedPhrase)) {
                GuardrailDecision(false, code = "OUTPUT_POLICY_BLOCKED")
            } else {
                GuardrailDecision.ALLOWED
            }
        },
        errorFactory = GatewayErrorFactory(FailurePolicy()),
    )
}

private data class GatewayInputs(
    val completeCommand: CompleteChatCommandIn,
    val command: StreamChatCommandIn,
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
) : DeploymentRegistryPort, RoutingSnapshotPort {
    override fun snapshot() = RoutingSnapshot(deployments)
    override fun current() = snapshot()
}

private class FakeInvoker(
    private val complete: (Deployment) -> ProviderResponse = { ProviderResponse("ok") },
    private val stream: (Deployment) -> Sequence<ProviderChunk> = { sequenceOf(ProviderChunk("ok")) },
    private val onStreamClose: () -> Unit = {},
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
    ): com.example.llmgateway.domain.stream.CloseableStream<ProviderChunk> {
        streamCalls += deployment.id.value
        return com.example.llmgateway.domain.stream.ManagedStream { scope ->
            scope.own(AutoCloseable { onStreamClose() })
            stream(deployment)
        }
    }
}

private object NoOpCircuitBreaker : CircuitBreakerPort

private object NoOpAdmissionOperator : RequestAdmissionOperator {
    override fun execute(request: CanonicalChatRequest, context: RequestContext) = Unit
}

private class RecordingObserver : AttemptObserverPort {
    val outcomes = mutableListOf<AttemptOutcome>()
    val attempts = mutableListOf<AttemptContext>()

    override fun start(context: AttemptContext): AttemptObservationHandle {
        attempts += context
        return object : AttemptObservationHandle {
            private val stopped = java.util.concurrent.atomic.AtomicBoolean()
            override fun stop(outcome: AttemptOutcome) {
                if (stopped.compareAndSet(false, true)) outcomes += outcome
            }
        }
    }
}
