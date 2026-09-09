package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.redis.RedisCircuitBreakerAdapter
import com.example.llmgateway.adapter.out.redis.RedisTokenBucketRateLimiter
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.error.FailureClass
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.CanonicalMessage
import com.example.llmgateway.domain.routing.CircuitPermit
import com.example.llmgateway.domain.routing.Deployment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class RedisAdaptersTest : FunSpec() {

    private val deployment = Deployment(
        id = DeploymentId("openai-redis-test"),
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
        test("Redis token bucket maps atomic script results to rate decisions") {
            val context = RequestContext(RequestId("req"), caller = "bff", tenant = "tenant")
            RedisTokenBucketRateLimiter(
                redisTemplate = ScriptedRedisTemplate(1L, -2L),
                enabled = true,
                requestsPerMinute = 60,
                burst = 1,
                keyPrefix = "test-rate",
                stateTtl = Duration.ofMinutes(1),
            ).let { limiter ->
                limiter.check(context, request).allowed shouldBe true
                val rejected = limiter.check(context, request)
                rejected.allowed shouldBe false
                rejected.retryAfterSeconds shouldBe 2
            }

            RedisTokenBucketRateLimiter(
                redisTemplate = ScriptedRedisTemplate(),
                enabled = false,
                requestsPerMinute = 60,
                burst = 1,
                keyPrefix = "test-rate",
                stateTtl = Duration.ofMinutes(1),
            ).check(context, request).allowed shouldBe true
        }

        test("Redis circuit breaker maps shared script state and disabled mode") {
            val circuit = RedisCircuitBreakerAdapter(
                redisTemplate = ScriptedRedisTemplate(1L, 0L, "generation", "", 1L, 1L, 1L),
                enabled = true,
                failureThreshold = 2,
                openDuration = Duration.ofSeconds(1),
                keyPrefix = "test-circuit",
                stateTtl = Duration.ofMinutes(1),
            )

            circuit.inspect(deployment) shouldBe true
            circuit.inspect(deployment) shouldBe false
            val permit = circuit.acquire(deployment, AttemptId("owner"))!!
            permit shouldBe CircuitPermit(AttemptId("owner"), "generation")
            circuit.acquire(deployment, AttemptId("other")) shouldBe null
            circuit.onSuccess(deployment, permit)
            circuit.onFailure(deployment, permit, FailureClass.TRANSIENT)
            circuit.onIgnored(deployment, permit)

            RedisCircuitBreakerAdapter(
                redisTemplate = ScriptedRedisTemplate(),
                enabled = false,
                failureThreshold = 2,
                openDuration = Duration.ofSeconds(1),
                keyPrefix = "test-circuit",
                stateTtl = Duration.ofMinutes(1),
            ).let {
                it.inspect(deployment) shouldBe true
                it.acquire(deployment, AttemptId("disabled")) shouldBe CircuitPermit(AttemptId("disabled"), "disabled")
            }
        }

        test("Redis backend failures fail closed for both admission and circuit acquisition") {
            val context = RequestContext(RequestId("backend-failure"), caller = "bff", tenant = "tenant")
            val rateLimit = RedisTokenBucketRateLimiter(
                redisTemplate = ScriptedRedisTemplate(),
                enabled = true,
                requestsPerMinute = 60,
                burst = 1,
                keyPrefix = "test-rate",
                stateTtl = Duration.ofMinutes(1),
            ).check(context, request)

            rateLimit.allowed shouldBe false
            rateLimit.backendAvailable shouldBe false

            val circuit = RedisCircuitBreakerAdapter(
                redisTemplate = ScriptedRedisTemplate(),
                enabled = true,
                failureThreshold = 2,
                openDuration = Duration.ofSeconds(1),
                keyPrefix = "test-circuit",
                stateTtl = Duration.ofMinutes(1),
            )
            circuit.inspect(deployment) shouldBe false
            circuit.acquire(deployment, AttemptId("unavailable")) shouldBe null
            val permit = CircuitPermit(AttemptId("old-owner"), "old-generation")
            circuit.onSuccess(deployment, permit)
            circuit.onFailure(deployment, permit, FailureClass.TRANSIENT)
            circuit.onIgnored(deployment, permit)
        }

        test("distributed state adapters reject unsafe configuration") {
            shouldThrow<IllegalArgumentException> {
                RedisTokenBucketRateLimiter(
                    redisTemplate = ScriptedRedisTemplate(),
                    enabled = true,
                    requestsPerMinute = 0,
                    burst = 1,
                    keyPrefix = "test-rate",
                    stateTtl = Duration.ofMinutes(1),
                )
            }
            shouldThrow<IllegalArgumentException> {
                RedisCircuitBreakerAdapter(
                    redisTemplate = ScriptedRedisTemplate(),
                    enabled = true,
                    failureThreshold = 1,
                    openDuration = Duration.ofSeconds(2),
                    keyPrefix = "test-circuit",
                    stateTtl = Duration.ofSeconds(2),
                )
            }
        }
    }
}
