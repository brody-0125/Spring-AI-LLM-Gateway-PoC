package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.application.operator.DefaultCompleteAttemptOperator
import com.example.llmgateway.application.operator.DefaultStreamAttemptOperator
import com.example.llmgateway.application.operator.RequestLifecycleOperator
import com.example.llmgateway.application.operator.VirtualThreadDeadlineOperator
import com.example.llmgateway.application.policy.DefaultFailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.DeploymentAvailabilityPort
import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.core.primitive.*
import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.accounting.CostSource
import com.example.llmgateway.domain.accounting.CostStatus
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.accounting.UsageComponent
import com.example.llmgateway.domain.accounting.UsageKey
import com.example.llmgateway.domain.accounting.UsageSource
import com.example.llmgateway.domain.accounting.UsageType
import com.example.llmgateway.domain.execution.AttemptCancelled
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.CanonicalMessage
import com.example.llmgateway.domain.inference.chat.GatewayCompleteEvent
import com.example.llmgateway.domain.inference.chat.GatewayDeltaEvent
import com.example.llmgateway.domain.inference.chat.ProviderChunk
import com.example.llmgateway.domain.inference.chat.ProviderResponse
import com.example.llmgateway.domain.observation.AttemptObservationHandle
import com.example.llmgateway.domain.observation.ObservationContext
import com.example.llmgateway.domain.observation.withObservationScope
import com.example.llmgateway.domain.routing.CircuitPermit
import com.example.llmgateway.domain.routing.Deployment
import com.example.llmgateway.domain.stream.CloseableStream
import com.example.llmgateway.domain.stream.ManagedStream
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ObservationLifecycleTest : FunSpec() {
    private val deployment = Deployment(
        DeploymentId("test"), Vendor.OPENAI, Dialect.OPENAI, ModelGroup("default"), "test",
    )
    private val request = CanonicalChatRequest(
        ModelGroup("default"), listOf(CanonicalMessage(MessageRole.USER, "not-for-telemetry")),
    )

    init {
        test("actual complete operator nests request and every attempt across provider virtual threads") {
            val stopped = CopyOnWriteArrayList<Observation.Context>()
            val registry = registry(stopped)
            val meters = SimpleMeterRegistry()
            val lifecycle = RequestLifecycleOperator(MicrometerRequestObserver(registry, meters))
            val context = context()
            val invoker = provider(complete = {
                Thread.currentThread().isVirtual shouldBe true
                registry.currentObservation?.context?.name shouldBe "llm.gateway.attempt"
                registry.currentObservation?.context?.parentObservation?.contextView?.name shouldBe "llm.gateway.request"
                ProviderResponse("ok", Usage(inputTokens = 2, outputTokens = 1))
            })
            val operator = completeOperator(invoker, registry, meters)
            val server = Observation.start("http.server.requests", registry)
            server.openScope().use {
                lifecycle.execute(request, context) {
                    listOf(AttemptKind.INITIAL, AttemptKind.RETRY, AttemptKind.FALLBACK).forEachIndexed { i, kind ->
                        operator.execute(request, context, deployment, i + 1, kind, null).text shouldBe "ok"
                        registry.currentObservation?.context?.name shouldBe "llm.gateway.request"
                    }
                }
                registry.currentObservation shouldBe server
            }
            registry.currentObservation shouldBe null
            server.stop()
            stopped.count { it.name == "llm.gateway.attempt" } shouldBe 3
            stopped.filter { it.name == "llm.gateway.attempt" }.all {
                it.parentObservation?.contextView?.parentObservation?.contextView?.name == "http.server.requests"
            } shouldBe true
            meters.get("llm.gateway.attempts").counter().count() shouldBe 3.0
            meters.get("llm.gateway.retries").counter().count() shouldBe 1.0
            meters.get("llm.gateway.fallbacks").counter().count() shouldBe 1.0
        }

        test("stream pulls restore caller scope and cross-thread close stops both handles once") {
            val stopped = CopyOnWriteArrayList<Observation.Context>()
            val registry = registry(stopped)
            val meters = SimpleMeterRegistry()
            val closes = AtomicInteger()
            val lifecycle = RequestLifecycleOperator(MicrometerRequestObserver(registry, meters))
            val invoker = provider(stream = {
                registry.currentObservation?.context?.name shouldBe "llm.gateway.attempt"
                ManagedStream { scope ->
                    scope.own(AutoCloseable { closes.incrementAndGet() })
                    sequence {
                        Thread.currentThread().isVirtual shouldBe true
                        registry.currentObservation?.context?.name shouldBe "llm.gateway.attempt"
                        yield(ProviderChunk("one"))
                        yield(ProviderChunk("two"))
                    }
                }
            })
            val operator = streamOperator(invoker, registry, meters)
            val context = context()
            val server = Observation.start("http.server.requests", registry)
            server.openScope().use {
                val stream = lifecycle.stream(request.copy(stream = true), context) {
                    operator.execute(request.copy(stream = true), context, deployment, 1, "response", AttemptKind.INITIAL, null)
                }
                val iterator = stream.iterator()
                iterator.hasNext() shouldBe true
                registry.currentObservation shouldBe server
                (iterator.next() as GatewayDeltaEvent).text shouldBe "one"
                registry.currentObservation shouldBe server
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    executor.submit {
                        stream.close()
                        stream.close()
                        registry.currentObservation shouldBe null
                    }.get(5, TimeUnit.SECONDS)
                }
                registry.currentObservation shouldBe server
                iterator.hasNext() shouldBe false
            }
            registry.currentObservation shouldBe null
            server.stop()
            closes.get() shouldBe 1
            stopped.count { it.name == "llm.gateway.attempt" } shouldBe 1
            stopped.count { it.name == "llm.gateway.request" } shouldBe 1
            meters.get("llm.gateway.requests").tag("outcome", "cancelled").counter().count() shouldBe 1.0
            meters.get("llm.gateway.attempt.ttft").timer().count() shouldBe 1L
        }

        test("first-token and terminal observer failures cannot fail a successful stream") {
            val registry = registry()
            val meters = SimpleMeterRegistry()
            val firstTokens = AtomicInteger()
            val calls = AtomicInteger()
            val observer = object : AttemptObserverPort {
                override fun start(context: AttemptContext) = object : AttemptObservationHandle {
                    override fun firstToken() { firstTokens.incrementAndGet(); error("observer first token") }
                    override fun stop(outcome: AttemptOutcome) { error("observer terminal") }
                }
            }
            val invoker = provider(stream = {
                calls.incrementAndGet()
                ManagedStream { sequenceOf(ProviderChunk("ok", usage = Usage(1, 1))) }
            })
            val events = streamOperator(invoker, registry, meters, observer).execute(
                request.copy(stream = true), context(), deployment, 1, "response", AttemptKind.INITIAL, null,
            ).use { it.toList() }
            events.size shouldBe 2
            (events.last() is GatewayCompleteEvent) shouldBe true
            firstTokens.get() shouldBe 1
            calls.get() shouldBe 1
        }

        test("post-provider circuit callback failure still terminates the owned observation") {
            val stopped = CopyOnWriteArrayList<Observation.Context>()
            val registry = registry(stopped)
            val meters = SimpleMeterRegistry()
            val breaker = object : CircuitBreakerPort {
                override fun onSuccess(deployment: Deployment, permit: CircuitPermit) { error("circuit unavailable") }
            }
            val operator = completeOperator(provider(), registry, meters, breaker)
            shouldThrow<IllegalStateException> {
                operator.execute(request, context(), deployment, 1, AttemptKind.INITIAL, null)
            }.message shouldBe "circuit unavailable"
            stopped.count { it.name == "llm.gateway.attempt" } shouldBe 1
            registry.currentObservation shouldBe null
        }

        test("capture restores an unrelated worker context on success and failure") {
            val registry = registry()
            val adapter = MicrometerObservationContextAdapter(registry)
            val source = Observation.start("source", registry)
            val captured = source.openScope().use { adapter.capture() }
            val empty = adapter.capture()
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                executor.submit {
                    val previous = Observation.start("previous", registry)
                    previous.openScope().use {
                        captured.withObservationScope { registry.currentObservation shouldBe source }
                        registry.currentObservation shouldBe previous
                        shouldThrow<IllegalStateException> {
                            captured.withObservationScope { error("business failure") }
                        }.message shouldBe "business failure"
                        registry.currentObservation shouldBe previous
                        empty.withObservationScope { registry.currentObservation shouldBe null }
                        registry.currentObservation shouldBe previous
                    }
                    registry.currentObservation shouldBe null
                    previous.stop()
                }.get(5, TimeUnit.SECONDS)
            }
            source.stop()
        }

        test("scope callback failures restore registry without replacing business result") {
            val registry = registry()
            var failOpen = false
            var failClose = false
            registry.observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onScopeOpened(context: Observation.Context) {
                    if (failOpen && context.name == "child") error("open handler")
                }
                override fun onScopeClosed(context: Observation.Context) {
                    if (failClose && context.name == "child") error("close handler")
                }
            })
            val parent = Observation.start("parent", registry)
            val child = Observation.start("child", registry)
            val captured = child.openScope().use { MicrometerObservationContextAdapter(registry).capture() }
            parent.openScope().use {
                failOpen = true
                captured.withObservationScope {
                    registry.currentObservation shouldBe parent
                    "open failure ignored"
                } shouldBe "open failure ignored"
                registry.currentObservation shouldBe parent
                failOpen = false
                failClose = true
                captured.withObservationScope {
                    registry.currentObservation shouldBe child
                    "close failure ignored"
                } shouldBe "close failure ignored"
                registry.currentObservation shouldBe parent
            }
            registry.currentObservation shouldBe null
            child.stop()
            parent.stop()
        }

        test("unknown usage is not fabricated as zero tokens or zero cost") {
            val registry = registry()
            val meters = SimpleMeterRegistry()
            val observer = MicrometerAttemptObserver(registry, meters)
            val usage = Usage(listOf(
                UsageComponent(UsageKey(UsageType.INPUT_TOKENS), 5),
                UsageComponent(UsageKey(UsageType.OUTPUT_TOKENS), null),
                UsageComponent(UsageKey(UsageType.RERANK_QUERIES), 2),
                UsageComponent(UsageKey(UsageType.TOOL_INVOCATIONS), 1, UsageSource.ESTIMATED),
            ))
            val handle = observer.start(attempt())
            handle.stop(AttemptSuccess(usage, Cost(java.math.BigDecimal.ONE)))
            handle.firstToken()
            handle.stop(AttemptCancelled)
            meters.find("llm.gateway.tokens").tag("direction", "output").counter() shouldBe null
            meters.get("llm.gateway.tokens").tag("direction", "input").counter().count() shouldBe 5.0
            meters.find("llm.gateway.cost.usd").summary() shouldBe null
            meters.get("llm.gateway.usage.missing").counter().count() shouldBe 1.0
            meters.get("llm.gateway.usage.units").tag("usage_type", "rerank_queries")
                .tag("unit", "query").tag("source", "provider_reported").counter().count() shouldBe 2.0
            meters.get("llm.gateway.usage.units").tag("usage_type", "tool_invocations")
                .tag("source", "estimated").counter().count() shouldBe 1.0
            meters.find("llm.gateway.attempt.ttft").timer() shouldBe null
            meters.get("llm.gateway.attempts").counter().count() shouldBe 1.0
            meters.meters.flatMap { it.id.tags }.any {
                it.key in setOf("request_id", "execution_id", "attempt_id", "variant", "key_id")
            } shouldBe false
        }

        test("partial cost stays separate from complete estimates and explicit free usage") {
            val meters = SimpleMeterRegistry()
            val observer = MicrometerAttemptObserver(registry(), meters)
            observer.start(attempt()).stop(AttemptSuccess(
                Usage(1, 1), Cost(java.math.BigDecimal("0.1"), CostStatus.PARTIAL, source = CostSource.RATE_CARD),
            ))
            observer.start(attempt()).stop(AttemptSuccess(
                Usage(0, 0), Cost(java.math.BigDecimal.ZERO, CostStatus.ESTIMATED, source = CostSource.RATE_CARD),
            ))
            meters.get("llm.gateway.cost.usd").tag("status", "partial").tag("source", "rate_card")
                .summary().totalAmount() shouldBe 0.1
            meters.get("llm.gateway.cost.usd").tag("status", "estimated").summary().count() shouldBe 1L
            meters.find("llm.gateway.cost.usd.total").tag("status", "partial").counter() shouldBe null
            meters.get("llm.gateway.cost.usd.total").tag("status", "estimated").counter().count() shouldBe 0.0
        }

        test("deadline cancellation closes the captured worker scope without altering the caller") {
            val registry = registry()
            val realContext = MicrometerObservationContextAdapter(registry)
            val cleaned = java.util.concurrent.CountDownLatch(1)
            val entered = java.util.concurrent.CountDownLatch(1)
            val restored = java.util.concurrent.atomic.AtomicBoolean()
            val deadline = VirtualThreadDeadlineOperator(
                com.example.llmgateway.application.port.out.ObservationContextPort {
                    val captured = realContext.capture()
                    ObservationContext {
                        val scope = captured.openScope()
                        AutoCloseable {
                            try {
                                scope.close()
                                restored.set(registry.currentObservation == null)
                            } finally {
                                cleaned.countDown()
                            }
                        }
                    }
                },
            )
            val parent = Observation.start("request", registry)
            parent.openScope().use {
                shouldThrow<java.util.concurrent.TimeoutException> {
                    deadline.execute(Instant.now().plusSeconds(1)) {
                        registry.currentObservation shouldBe parent
                        entered.countDown()
                        java.util.concurrent.CountDownLatch(1).await()
                    }
                }
                entered.count shouldBe 0L
                cleaned.await(5, TimeUnit.SECONDS) shouldBe true
                restored.get() shouldBe true
                registry.currentObservation shouldBe parent
            }
            registry.currentObservation shouldBe null
            parent.stop()
        }
    }

    private fun context() = RequestContext(RequestId("shared"), deadline = Instant.now().plusSeconds(10))

    private fun attempt() = AttemptContext(
        requestId = RequestId("shared"), attemptId = AttemptId("attempt"),
        deployment = deployment, sequence = 1, executionId = ExecutionId.newId(), kind = AttemptKind.INITIAL,
    )

    private fun registry(stopped: MutableList<Observation.Context> = CopyOnWriteArrayList()): ObservationRegistry =
        ObservationRegistry.create().also { registry ->
            registry.observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onStop(context: Observation.Context) { stopped += context }
            })
        }

    private fun provider(
        complete: () -> ProviderResponse = { ProviderResponse("ok") },
        stream: () -> CloseableStream<ProviderChunk> = { ManagedStream { sequenceOf(ProviderChunk("ok")) } },
    ) = object : ProviderInvokerPort {
        override fun complete(deployment: Deployment, request: CanonicalChatRequest, attempt: AttemptContext) = complete()
        override fun stream(deployment: Deployment, request: CanonicalChatRequest, attempt: AttemptContext) = stream()
    }

    private fun completeOperator(
        provider: ProviderInvokerPort,
        registry: ObservationRegistry,
        meters: SimpleMeterRegistry,
        breaker: CircuitBreakerPort = object : CircuitBreakerPort {},
    ) = DefaultCompleteAttemptOperator(
        provider, DefaultFailureClassifier(), MicrometerAttemptObserver(registry, meters),
        VirtualThreadDeadlineOperator(MicrometerObservationContextAdapter(registry)),
        breaker, FailurePolicy(), DeploymentAvailabilityPort { true },
        TestAttemptJournal(),
    )

    private fun streamOperator(
        provider: ProviderInvokerPort,
        registry: ObservationRegistry,
        meters: SimpleMeterRegistry,
        observer: AttemptObserverPort = MicrometerAttemptObserver(registry, meters),
    ) = DefaultStreamAttemptOperator(
        provider, DefaultFailureClassifier(), observer,
        VirtualThreadDeadlineOperator(MicrometerObservationContextAdapter(registry)),
        object : CircuitBreakerPort {}, FailurePolicy(), DeploymentAvailabilityPort { true },
        TestAttemptJournal(),
    )
}
