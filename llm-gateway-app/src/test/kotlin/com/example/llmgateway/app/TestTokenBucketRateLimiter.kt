package com.example.llmgateway.app

import com.example.llmgateway.application.port.out.RateLimiterPort
import com.example.llmgateway.domain.model.RateLimitDecision
import com.example.llmgateway.domain.model.RequestContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

internal class TestTokenBucketRateLimiter(
    private val enabled: Boolean,
    requestsPerMinute: Int,
    private val burst: Int,
) : RateLimiterPort {

    private data class Bucket(var tokens: Double, var lastRefillNanos: Long)

    private val requestsPerSecond = requestsPerMinute / 60.0
    private val refillRatePerNanosecond = requestsPerSecond / 1_000_000_000.0
    private val buckets = ConcurrentHashMap<String, Bucket>()

    init {
        require(requestsPerMinute > 0)
        require(burst > 0)
    }

    override fun check(context: RequestContext): RateLimitDecision {
        if (!enabled) return RateLimitDecision.ALLOWED
        val bucket = buckets.computeIfAbsent("${context.tenant}:${context.caller}") {
            Bucket(burst.toDouble(), System.nanoTime())
        }
        synchronized(bucket) {
            val now = System.nanoTime()
            val elapsed = (now - bucket.lastRefillNanos).coerceAtLeast(0)
            bucket.tokens = (bucket.tokens + elapsed * refillRatePerNanosecond).coerceAtMost(burst.toDouble())
            bucket.lastRefillNanos = now
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1
                return RateLimitDecision.ALLOWED
            }
            return RateLimitDecision(
                allowed = false,
                retryAfterSeconds = ceil((1 - bucket.tokens) / requestsPerSecond).toLong().coerceAtLeast(1),
            )
        }
    }
}
