package com.example.llmgateway.application.policy

import com.example.llmgateway.domain.error.FailureClass
import com.example.llmgateway.domain.error.RequestDisposition
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

class AttemptPolicy(
    private val failurePolicy: FailurePolicy,
    val maxTotalAttempts: Int = 3,
    private val maxRetriesPerDeployment: Int = 1,
    private val maxFallbacks: Int = 2,
    private val initialBackoff: Duration = Duration.ofMillis(100),
    private val backoffMultiplier: Double = 2.0,
    private val maxBackoff: Duration = Duration.ofSeconds(2),
    private val perAttemptTimeout: Duration = Duration.ofSeconds(30),
    private val completionReserve: Duration = Duration.ZERO,
    private val sleeper: (Duration) -> Unit = { delay ->
        Thread.sleep(delay.toMillis())
    },
    private val jitter: (Long) -> Long = { bound ->
        if (bound <= 0) 0 else ThreadLocalRandom.current().nextLong(bound + 1)
    },
) {
    init {
        require(!completionReserve.isNegative) { "completionReserve must not be negative" }
        require(maxTotalAttempts > 0) { "maxTotalAttempts must be positive" }
        require(maxRetriesPerDeployment >= 0) { "maxRetriesPerDeployment must not be negative" }
        require(maxFallbacks >= 0) { "maxFallbacks must not be negative" }
        require(!initialBackoff.isNegative) { "initialBackoff must not be negative" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be at least 1" }
        require(!maxBackoff.isNegative) { "maxBackoff must not be negative" }
        require(initialBackoff <= maxBackoff) { "initialBackoff must not exceed maxBackoff" }
        require(!perAttemptTimeout.isZero && !perAttemptTimeout.isNegative) {
            "perAttemptTimeout must be positive"
        }
    }

    fun newBudget(): AttemptBudget = AttemptBudget(maxTotalAttempts, maxFallbacks)

    fun deadlineFor(requestDeadline: Instant, startedAt: Instant): Instant =
        minOf(requestDeadline.minus(completionReserve), startedAt.plus(perAttemptTimeout))

    fun canRetrySameDeployment(
        failureClass: FailureClass,
        emitted: Boolean,
        requestDisposition: RequestDisposition,
        retryIndex: Int,
        budget: AttemptBudget,
    ): Boolean =
        retryIndex < maxRetriesPerDeployment &&
            failurePolicy.clientRetryable(failureClass) &&
            emitted.not() &&
            requestDisposition == RequestDisposition.NOT_SENT &&
            budget.attempts < maxTotalAttempts

    fun canFallback(
        failureClass: FailureClass,
        emitted: Boolean,
        budget: AttemptBudget,
    ): Boolean =
        failurePolicy.fallbackEligible(failureClass) &&
            emitted.not() &&
            budget.fallbacks < maxFallbacks &&
            budget.attempts < maxTotalAttempts

    fun delay(
        retryAfter: Duration?,
        retryIndex: Int,
        remaining: Duration,
    ): Duration? {
        val calculated = exponentialBackoff(retryIndex)
        val jittered = Duration.ofMillis(jitter(calculated.toMillis()))
        val requested = maxOf(jittered, retryAfter ?: Duration.ZERO)
        val available = remaining.minusMillis(50)
        return requested.takeIf { !available.isNegative && !available.isZero && it <= available }
    }

    fun await(delay: Duration) {
        if (!delay.isZero) sleeper(delay)
    }

    private fun exponentialBackoff(retryIndex: Int): Duration {
        var millis = initialBackoff.toMillis()
        repeat(retryIndex) {
            millis = (millis.toDouble() * backoffMultiplier)
                .toLong()
                .coerceAtLeast(millis)
                .coerceAtMost(maxBackoff.toMillis())
        }
        return Duration.ofMillis(millis)
    }
}
