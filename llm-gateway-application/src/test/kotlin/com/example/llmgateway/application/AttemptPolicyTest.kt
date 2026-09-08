package com.example.llmgateway.application

import com.example.llmgateway.application.operator.AttemptFailureException
import com.example.llmgateway.application.policy.AttemptPolicy
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.RequestDisposition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class AttemptPolicyTest : FunSpec({
    test("attempt budget limits total attempts and fallbacks") {
        val budget = AttemptPolicy(
            failurePolicy = FailurePolicy(),
            maxTotalAttempts = 2,
            maxFallbacks = 5,
        ).newBudget()

        budget.startAttempt() shouldBe true
        budget.startFallback() shouldBe true
        budget.startAttempt() shouldBe true
        budget.startFallback() shouldBe false
        budget.startAttempt() shouldBe false
    }

    test("same-deployment retry requires a request that was not sent") {
        val policy = AttemptPolicy(
            failurePolicy = FailurePolicy(),
            sleeper = {},
        )
        val budget = policy.newBudget().also { it.startAttempt() }
        val failure = AttemptFailureException(
            failureClass = FailureClass.TRANSIENT,
            requestDisposition = RequestDisposition.NOT_SENT,
            cause = IllegalStateException("connection failed"),
        )

        policy.canRetrySameDeployment(
            failureClass = failure.failureClass,
            emitted = failure.emitted,
            requestDisposition = failure.requestDisposition,
            retryIndex = 0,
            budget = budget,
        ) shouldBe true
        policy.canRetrySameDeployment(
            failureClass = failure.failureClass,
            emitted = failure.emitted,
            requestDisposition = RequestDisposition.SENT_UNKNOWN,
            retryIndex = 0,
            budget = budget,
        ) shouldBe false
    }

    test("retry delay honors provider delay and remaining deadline") {
        val policy = AttemptPolicy(
            failurePolicy = FailurePolicy(),
            initialBackoff = Duration.ofMillis(100),
            maxBackoff = Duration.ofSeconds(1),
            jitter = { it },
            sleeper = {},
        )
        val failure = AttemptFailureException(
            failureClass = FailureClass.TRANSIENT,
            retryAfter = Duration.ofMillis(400),
            cause = IllegalStateException("connection failed"),
        )

        val delay = policy.delay(failure.retryAfter, retryIndex = 0, remaining = Duration.ofSeconds(1))

        delay.shouldNotBeNull()
        delay shouldBe Duration.ofMillis(400)
        policy.delay(failure.retryAfter, retryIndex = 0, remaining = Duration.ofMillis(300)) shouldBe null
    }

    test("attempt deadline is capped by both request and per-attempt deadlines") {
        val policy = AttemptPolicy(
            failurePolicy = FailurePolicy(),
            perAttemptTimeout = Duration.ofSeconds(5),
        )
        val startedAt = Instant.parse("2026-01-01T00:00:00Z")

        policy.deadlineFor(startedAt.plusSeconds(30), startedAt) shouldBe startedAt.plusSeconds(5)
        policy.deadlineFor(startedAt.plusSeconds(2), startedAt) shouldBe startedAt.plusSeconds(2)
    }
})
