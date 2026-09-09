package com.example.llmgateway.adapter.out.redis

import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.domain.error.FailureClass
import com.example.llmgateway.domain.routing.CircuitPermit
import com.example.llmgateway.domain.routing.Deployment
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript

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
    private val inspectScript = DefaultRedisScript<Long>(INSPECT_SCRIPT, Long::class.java)
    private val acquireScript = DefaultRedisScript<String>(ACQUIRE_SCRIPT, String::class.java)
    private val finishScript = DefaultRedisScript<Long>(FINISH_SCRIPT, Long::class.java)

    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(!openDuration.isNegative && !openDuration.isZero) { "openDuration must be positive" }
        require(!stateTtl.isNegative && !stateTtl.isZero) { "stateTtl must be positive" }
        require(stateTtl > openDuration) { "stateTtl must be longer than openDuration" }
        require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank" }
        require('{' !in keyPrefix && '}' !in keyPrefix) { "keyPrefix must not contain Redis hash tags" }
    }

    override fun inspect(deployment: Deployment): Boolean {
        if (!enabled) return true
        return try {
            redisTemplate.execute(inspectScript, listOf(key(deployment.id.value))) == 1L
        } catch (error: Exception) {
            recordBackendFailure(deployment, "inspect", error)
            false
        }
    }

    override fun acquire(deployment: Deployment, owner: AttemptId): CircuitPermit? {
        if (!enabled) return CircuitPermit(owner, "disabled")
        return try {
            val generation = redisTemplate.execute(
                acquireScript, keys(deployment, owner),
                owner.value, UUID.randomUUID().toString(), stateTtl.toMillis().toString(),
            )
            generation?.takeIf { it.isNotEmpty() }?.let { CircuitPermit(owner, it) }
        } catch (error: Exception) {
            recordBackendFailure(deployment, "acquire", error)
            null
        }
    }

    override fun onSuccess(deployment: Deployment, permit: CircuitPermit) = finish(deployment, permit, "success")

    override fun onFailure(deployment: Deployment, permit: CircuitPermit, failure: FailureClass) =
        finish(deployment, permit, "failure")

    override fun onIgnored(deployment: Deployment, permit: CircuitPermit) = finish(deployment, permit, "ignored")

    private fun finish(deployment: Deployment, permit: CircuitPermit, outcome: String) {
        if (!enabled) return
        try {
            redisTemplate.execute(
                finishScript, keys(deployment, permit.owner),
                permit.owner.value,
                permit.generation,
                outcome,
                failureThreshold.toString(),
                openDuration.toMillis().coerceAtLeast(1).toString(),
                stateTtl.toMillis().toString(),
                UUID.randomUUID().toString(),
            ) ?: throw IllegalStateException("Redis circuit-breaker script returned no result")
        } catch (error: Exception) {
            recordBackendFailure(deployment, outcome, error)
        }
    }

    // Hashing the entire existing state key places the receipt in its Redis Cluster slot.
    private fun keys(deployment: Deployment, owner: AttemptId): List<String> {
        val stateKey = key(deployment.id.value)
        return listOf(stateKey, "{$stateKey}:permit:${sha256(owner.value)}")
    }

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
        const val INSPECT_SCRIPT = """
            local server_time = redis.call('TIME')
            local now = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local open_until = tonumber(redis.call('HGET', KEYS[1], 'open_until_millis') or '0')
            if open_until > now then return 0 end
            if open_until > 0 then
                if redis.call('HEXISTS', KEYS[1], 'probe_owner') == 1 then return 0 end
                if redis.call('HGET', KEYS[1], 'half_open_probe') == '1' then return 0 end
            end
            return 1
        """

        const val ACQUIRE_SCRIPT = """
            local server_time = redis.call('TIME')
            local now = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            local open_until = tonumber(redis.call('HGET', KEYS[1], 'open_until_millis') or '0')
            if open_until > now then return '' end
            local generation = redis.call('HGET', KEYS[1], 'generation')
            local receipt = redis.call('GET', KEYS[2])
            if receipt and receipt == generation then return generation end
            if open_until > 0 then
                if redis.call('HEXISTS', KEYS[1], 'probe_owner') == 1 then return '' end
                if redis.call('HGET', KEYS[1], 'half_open_probe') == '1' then return '' end
            end
            if not generation then
                generation = ARGV[2]
                redis.call('HSET', KEYS[1], 'generation', generation)
            end
            if open_until > 0 then
                redis.call('HSET', KEYS[1], 'probe_owner', ARGV[1])
                redis.call('PERSIST', KEYS[1])
            else
                redis.call('PEXPIRE', KEYS[1], ARGV[3])
            end
            redis.call('SET', KEYS[2], generation, 'PX', ARGV[3])
            return generation
        """

        const val FINISH_SCRIPT = """
            local generation = redis.call('HGET', KEYS[1], 'generation')
            if not generation or generation ~= ARGV[2] then return 0 end
            if redis.call('GET', KEYS[2]) ~= generation then return 0 end
            local probe_owner = redis.call('HGET', KEYS[1], 'probe_owner')
            if probe_owner and probe_owner ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[2])
            local outcome = ARGV[3]
            if outcome == 'success' then
                redis.call('HSET', KEYS[1], 'consecutive_failures', 0, 'open_until_millis', 0)
                redis.call('HDEL', KEYS[1], 'probe_owner', 'half_open_probe')
                if probe_owner then redis.call('HSET', KEYS[1], 'generation', ARGV[7]) end
                redis.call('PEXPIRE', KEYS[1], ARGV[6])
                return 1
            end
            if outcome == 'ignored' and not probe_owner then return 1 end
            local failures = tonumber(redis.call('HGET', KEYS[1], 'consecutive_failures') or '0')
            if outcome == 'failure' then failures = failures + 1 end
            redis.call('HSET', KEYS[1], 'consecutive_failures', failures)
            if not probe_owner and failures < tonumber(ARGV[4]) then
                redis.call('PEXPIRE', KEYS[1], ARGV[6])
                return 1
            end
            local server_time = redis.call('TIME')
            local now = tonumber(server_time[1]) * 1000 + math.floor(tonumber(server_time[2]) / 1000)
            redis.call('HSET', KEYS[1],
                'open_until_millis', now + tonumber(ARGV[5]),
                'generation', ARGV[7])
            redis.call('HDEL', KEYS[1], 'probe_owner', 'half_open_probe')
            redis.call('PERSIST', KEYS[1])
            return 1
        """
    }
}
