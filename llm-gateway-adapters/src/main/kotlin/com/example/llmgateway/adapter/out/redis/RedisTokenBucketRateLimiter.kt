package com.example.llmgateway.adapter.out.redis

import com.example.llmgateway.application.port.out.RateLimiterPort
import com.example.llmgateway.domain.model.RateLimitDecision
import com.example.llmgateway.domain.model.RequestContext
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

class RedisTokenBucketRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    private val enabled: Boolean,
    private val requestsPerMinute: Int,
    private val burst: Int,
    private val keyPrefix: String,
    private val stateTtl: Duration,
) : RateLimiterPort {

    private val script = DefaultRedisScript<Long>(SCRIPT, Long::class.java)

    init {
        require(requestsPerMinute > 0) { "requestsPerMinute must be positive" }
        require(burst > 0) { "burst must be positive" }
        require(!stateTtl.isNegative && !stateTtl.isZero) { "stateTtl must be positive" }
        require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank" }
    }

    override fun check(context: RequestContext): RateLimitDecision {
        if (!enabled) return RateLimitDecision.ALLOWED

        val result = redisTemplate.execute(
            script,
            listOf(key(context)),
            requestsPerMinute.toString(),
            burst.toString(),
            stateTtl.seconds.coerceAtLeast(1).toString(),
        ) ?: throw IllegalStateException("Redis rate-limit script returned no result")

        return if (result > 0) {
            RateLimitDecision.ALLOWED
        } else {
            RateLimitDecision(
                allowed = false,
                retryAfterSeconds = (-result).coerceAtLeast(1),
            )
        }
    }

    private fun key(context: RequestContext): String =
        "$keyPrefix:${sha256("${context.tenant}:${context.caller}")}"

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val SCRIPT = """
            local capacity = tonumber(ARGV[2])
            local requests_per_minute = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[3])
            local server_time = redis.call('TIME')
            local now = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill_millis')
            local tokens = tonumber(state[1])
            local last_refill = tonumber(state[2])
            if tokens == nil then tokens = capacity end
            if last_refill == nil then last_refill = now end
            local elapsed = math.max(0, now - last_refill)
            tokens = math.min(capacity, tokens + elapsed * requests_per_minute / 60000.0)
            if tokens >= 1.0 then
                tokens = tokens - 1.0
                redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill_millis', now)
                redis.call('EXPIRE', KEYS[1], ttl)
                return 1
            end
            local retry_after = math.ceil((1.0 - tokens) * 60000.0 / requests_per_minute / 1000.0)
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill_millis', now)
            redis.call('EXPIRE', KEYS[1], ttl)
            return -math.max(1, retry_after)
        """
    }
}
