package com.example.llmgateway.adapter.out.redis

import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.FailureClass
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

class RedisCircuitBreakerAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val enabled: Boolean,
    private val failureThreshold: Int,
    private val openDuration: Duration,
    private val keyPrefix: String,
    private val stateTtl: Duration,
    private val meterRegistry: MeterRegistry? = null,
) : CircuitBreakerPort {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val allowScript = DefaultRedisScript<Long>(ALLOW_SCRIPT, Long::class.java)
    private val successScript = DefaultRedisScript<Long>(SUCCESS_SCRIPT, Long::class.java)
    private val failureScript = DefaultRedisScript<Long>(FAILURE_SCRIPT, Long::class.java)

    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(!openDuration.isNegative && !openDuration.isZero) { "openDuration must be positive" }
        require(!stateTtl.isNegative && !stateTtl.isZero) { "stateTtl must be positive" }
        require(stateTtl > openDuration) { "stateTtl must be longer than openDuration" }
        require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank" }
    }

    override fun allow(deployment: Deployment): Boolean {
        if (!enabled) return true
        return try {
            execute(allowScript, deployment.id.value, stateTtl.seconds.coerceAtLeast(1).toString()) > 0
        } catch (error: Exception) {
            recordBackendFailure(deployment, "allow", error)
            true
        }
    }

    override fun onSuccess(deployment: Deployment) {
        if (!enabled) return
        try {
            execute(successScript, deployment.id.value, "")
        } catch (error: Exception) {
            recordBackendFailure(deployment, "success", error)
        }
    }

    override fun onFailure(deployment: Deployment, failure: FailureClass) {
        if (!enabled) return
        try {
            execute(
                failureScript,
                deployment.id.value,
                failureThreshold.toString(),
                openDuration.toMillis().coerceAtLeast(1).toString(),
                stateTtl.seconds.coerceAtLeast(1).toString(),
            )
        } catch (error: Exception) {
            recordBackendFailure(deployment, "failure", error)
        }
    }

    private fun execute(script: DefaultRedisScript<Long>, deploymentId: String, vararg arguments: String): Long =
        redisTemplate.execute(script, listOf(key(deploymentId)), *arguments)
            ?: throw IllegalStateException("Redis circuit-breaker script returned no result")

    private fun key(deploymentId: String): String = "$keyPrefix:${sha256(deploymentId)}"

    private fun recordBackendFailure(deployment: Deployment, operation: String, error: Exception) {
        meterRegistry?.let {
            Counter.builder("llm.gateway.circuit_breaker.backend.failure")
                .description("Circuit-breaker backend operations that failed")
                .tags("operation", operation)
                .register(it)
                .increment()
        }
        logger.warn(
            "llm_gateway_circuit_backend_failed deployment_id={} operation={} error_type={}",
            deployment.id.value,
            operation,
            error.javaClass.simpleName,
        )
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val ALLOW_SCRIPT = """
            local server_time = redis.call('TIME')
            local now = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local open_until = tonumber(redis.call('HGET', KEYS[1], 'open_until_millis') or '0')
            if open_until > now then return 0 end
            if open_until > 0 then
                if redis.call('HGET', KEYS[1], 'half_open_probe') == '1' then return 0 end
                redis.call('HSET', KEYS[1], 'half_open_probe', '1')
                return 1
            end
            return 1
        """

        const val SUCCESS_SCRIPT = """
            redis.call('DEL', KEYS[1])
            return 1
        """

        const val FAILURE_SCRIPT = """
            local threshold = tonumber(ARGV[1])
            local open_duration = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])
            local server_time = redis.call('TIME')
            local now = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local failures = tonumber(redis.call('HGET', KEYS[1], 'consecutive_failures') or '0') + 1
            local open_until = tonumber(redis.call('HGET', KEYS[1], 'open_until_millis') or '0')
            if failures >= threshold then open_until = now + open_duration end
            redis.call('HSET', KEYS[1],
                'consecutive_failures', failures,
                'open_until_millis', open_until,
                'half_open_probe', '0')
            redis.call('EXPIRE', KEYS[1], ttl)
            return failures
        """
    }
}
