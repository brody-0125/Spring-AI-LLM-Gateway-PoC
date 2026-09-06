package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.postgres.PostgresDeploymentRegistryAdapter
import com.example.llmgateway.adapter.out.redis.RedisCircuitBreakerAdapter
import com.example.llmgateway.adapter.out.redis.RedisTokenBucketRateLimiter
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.DeploymentOverride
import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.RequestContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import java.util.concurrent.Executors

class DistributedAdaptersIntegrationTest : FunSpec() {

    private val enabled = System.getenv("RUN_TESTCONTAINERS") == "true"
    private val redis = GenericContainer<Nothing>("redis:7.4-alpine").apply {
        withExposedPorts(6379)
        waitingFor(Wait.forListeningPort())
    }
    private val postgres = GenericContainer<Nothing>("postgres:16-alpine").apply {
        withEnv("POSTGRES_DB", "llm_gateway")
        withEnv("POSTGRES_USER", "llm_gateway")
        withEnv("POSTGRES_PASSWORD", "llm_gateway")
        withExposedPorts(5432)
        waitingFor(Wait.forListeningPort())
    }

    init {
        beforeSpec {
            if (enabled) {
                redis.start()
                postgres.start()
            }
        }
        afterSpec {
            if (enabled) {
                redis.stop()
                postgres.stop()
            }
        }

        test("Redis rate limit state is shared by independent adapter instances").config(enabled = enabled) {
            val first = RedisTokenBucketRateLimiter(redisTemplate(), true, 60, 1, "test-rate", Duration.ofMinutes(1))
            val second = RedisTokenBucketRateLimiter(redisTemplate(), true, 60, 1, "test-rate", Duration.ofMinutes(1))
            val context = RequestContext(RequestId("req"), caller = "bff", tenant = "tenant")

            first.check(context).allowed shouldBe true
            second.check(context).allowed shouldBe false
            second.check(context).retryAfterSeconds shouldBe 1
        }

        test("Redis rate limit remains atomic under concurrent callers").config(enabled = enabled) {
            val limiters = (1..4).map {
                RedisTokenBucketRateLimiter(
                    redisTemplate = redisTemplate(),
                    enabled = true,
                    requestsPerMinute = 60,
                    burst = 5,
                    keyPrefix = "test-rate-concurrent",
                    stateTtl = Duration.ofMinutes(1),
                )
            }
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val futures = (1..20).map { index ->
                executor.submit<Boolean> {
                    limiters[index % limiters.size].check(
                        RequestContext(RequestId("req-$index"), caller = "bff", tenant = "tenant"),
                    ).allowed
                }
            }

            val allowed = futures.count { it.get() }
            executor.close()
            check(allowed <= 5) { "atomic token bucket allowed $allowed requests" }
        }

        test("Redis circuit breaker shares open and half-open state").config(enabled = enabled) {
            val first = RedisCircuitBreakerAdapter(
                redisTemplate = redisTemplate(),
                enabled = true,
                failureThreshold = 2,
                openDuration = Duration.ofMillis(100),
                keyPrefix = "test-circuit",
                stateTtl = Duration.ofMinutes(1),
            )
            val second = RedisCircuitBreakerAdapter(
                redisTemplate = redisTemplate(),
                enabled = true,
                failureThreshold = 2,
                openDuration = Duration.ofMillis(100),
                keyPrefix = "test-circuit",
                stateTtl = Duration.ofMinutes(1),
            )

            first.allow(deployment) shouldBe true
            first.onFailure(deployment, FailureClass.TRANSIENT)
            second.allow(deployment) shouldBe true
            second.onFailure(deployment, FailureClass.TRANSIENT)
            first.allow(deployment) shouldBe false
            Thread.sleep(120)
            first.allow(deployment) shouldBe true
            second.allow(deployment) shouldBe false
            first.onSuccess(deployment)
            second.allow(deployment) shouldBe true
        }

        test("PostgreSQL registry persists routing overrides across adapter instances")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val transactionManager = DataSourceTransactionManager(jdbc.dataSource!!)
                val first = PostgresDeploymentRegistryAdapter(jdbc, transactionManager, listOf(deployment))
                first.initialize()

                first.snapshot().version shouldBe 1
                first.update(listOf(DeploymentOverride(deployment.id, enabled = false, weight = 0))).version shouldBe 2

                val second = PostgresDeploymentRegistryAdapter(jdbc, transactionManager, listOf(deployment))
                second.snapshot().deployments.single().enabled shouldBe false
                shouldThrow<IllegalArgumentException> {
                    second.update(listOf(DeploymentOverride(DeploymentId("unknown"), enabled = true)))
                }

                val executor = Executors.newVirtualThreadPerTaskExecutor()
                val updates = listOf(first, second).mapIndexed { index, registry ->
                    executor.submit {
                        registry.update(
                            listOf(DeploymentOverride(deployment.id, enabled = index == 0)),
                        )
                    }
                }
                updates.forEach { it.get() }
                executor.close()
                second.snapshot().version shouldBe 4L
            }
    }

    private fun redisTemplate(): StringRedisTemplate {
        val factory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379))
        factory.afterPropertiesSet()
        return StringRedisTemplate(factory).also { it.afterPropertiesSet() }
    }

    private fun postgresJdbcTemplate(): JdbcTemplate = DriverManagerDataSource().let { dataSource ->
        dataSource.setDriverClassName("org.postgresql.Driver")
        dataSource.url = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/llm_gateway"
        dataSource.username = "llm_gateway"
        dataSource.password = "llm_gateway"
        JdbcTemplate(dataSource)
    }

    private fun createSchema(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            CREATE TABLE llm_gateway_deployment (
                id VARCHAR(128) PRIMARY KEY,
                vendor VARCHAR(64) NOT NULL,
                dialect VARCHAR(64) NOT NULL,
                model_group VARCHAR(128) NOT NULL,
                model VARCHAR(256) NOT NULL,
                enabled BOOLEAN NOT NULL,
                weight INTEGER NOT NULL,
                supports_streaming BOOLEAN NOT NULL,
                input_cost_per_1k_usd NUMERIC(18, 8) NOT NULL,
                output_cost_per_1k_usd NUMERIC(18, 8) NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TABLE llm_gateway_routing_version (
                id SMALLINT PRIMARY KEY,
                version BIGINT NOT NULL
            )
            """.trimIndent(),
        )
        jdbc.update("INSERT INTO llm_gateway_routing_version (id, version) VALUES (1, 1)")
    }

    private val deployment = Deployment(
        id = DeploymentId("openai-test"),
        vendor = Vendor.OPENAI,
        dialect = Dialect.OPENAI,
        modelGroup = ModelGroup("default"),
        model = "gpt-test",
    )
}
