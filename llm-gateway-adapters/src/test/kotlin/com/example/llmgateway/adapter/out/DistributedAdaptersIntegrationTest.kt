package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.postgres.PostgresAttemptAccountingAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresDeploymentRegistryAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresPricingCatalogAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresRequestAccountingAdapter
import com.example.llmgateway.adapter.out.redis.RedisCircuitBreakerAdapter
import com.example.llmgateway.adapter.out.redis.RedisTokenBucketRateLimiter
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.DeploymentOverride
import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.AttemptOutcome
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.CanonicalMessage
import com.example.llmgateway.domain.model.Cost
import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RequestOutcome
import com.example.llmgateway.domain.model.RequestOutcomeStatus
import com.example.llmgateway.domain.model.Usage
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
import java.time.Clock
import java.time.Instant
import java.math.BigDecimal
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

            first.check(context, request).allowed shouldBe true
            second.check(context, request).allowed shouldBe false
            second.check(context, request).retryAfterSeconds shouldBe 1
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
                        request,
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

        test("PostgreSQL accounting is idempotent and keeps unknown cost state")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val transactionManager = DataSourceTransactionManager(jdbc.dataSource!!)
                val accounting = PostgresAttemptAccountingAdapter(
                    jdbcTemplate = jdbc,
                    transactionTemplate = org.springframework.transaction.support.TransactionTemplate(transactionManager),
                )
                val context = AttemptContext(
                    requestId = RequestId("accounting-request"),
                    attemptId = AttemptId("accounting-attempt"),
                    sequence = 1,
                    deployment = deployment,
                    caller = "bff",
                    tenant = "tenant-a",
                    traceId = "trace-accounting",
                )
                val outcome = AttemptOutcome.Success(
                    usage = Usage(inputTokens = 12, outputTokens = 8),
                    cost = Cost(status = com.example.llmgateway.domain.model.CostStatus.UNKNOWN),
                )

                accounting.record(context, outcome)
                accounting.record(context, outcome)

                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM llm_gateway_attempt_usage WHERE request_id = ? AND attempt_id = ?",
                    Long::class.java,
                    "accounting-request",
                    "accounting-attempt",
                ) shouldBe 1L
                jdbc.queryForObject(
                    "SELECT cost_status FROM llm_gateway_attempt_usage WHERE request_id = ? AND attempt_id = ?",
                    String::class.java,
                    "accounting-request",
                    "accounting-attempt",
                ) shouldBe "UNKNOWN"
            }

        test("PostgreSQL request accounting aggregates all provider attempts idempotently")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val transactionManager = DataSourceTransactionManager(jdbc.dataSource!!)
                val attemptAccounting = PostgresAttemptAccountingAdapter(
                    jdbcTemplate = jdbc,
                    transactionTemplate = org.springframework.transaction.support.TransactionTemplate(transactionManager),
                )
                val requestAccounting = PostgresRequestAccountingAdapter(
                    jdbcTemplate = jdbc,
                    transactionTemplate = org.springframework.transaction.support.TransactionTemplate(transactionManager),
                    clock = Clock.fixed(Instant.parse("2026-01-01T00:00:10Z"), java.time.ZoneOffset.UTC),
                )
                val requestContext = RequestContext(
                    requestId = RequestId("aggregate-request"),
                    caller = "bff",
                    tenant = "tenant-a",
                    startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    deadline = Instant.parse("2026-01-01T00:01:00Z"),
                )
                val request = CanonicalChatRequest(
                    modelGroup = ModelGroup("default"),
                    messages = listOf(CanonicalMessage(MessageRole.USER, "hello")),
                )
                val firstAttempt = AttemptContext(
                    requestId = requestContext.requestId,
                    attemptId = AttemptId("aggregate-attempt-1"),
                    sequence = 1,
                    deployment = deployment,
                    startedAt = Instant.parse("2026-01-01T00:00:01Z"),
                )
                val secondAttempt = firstAttempt.copy(
                    attemptId = AttemptId("aggregate-attempt-2"),
                    sequence = 2,
                    startedAt = Instant.parse("2026-01-01T00:00:02Z"),
                )
                attemptAccounting.record(
                    firstAttempt,
                    AttemptOutcome.Failure(
                        failureClass = FailureClass.TRANSIENT,
                        usage = Usage(inputTokens = 10, outputTokens = 0),
                        cost = Cost(
                            usd = BigDecimal("0.001"),
                            inputUsd = BigDecimal("0.001"),
                            status = com.example.llmgateway.domain.model.CostStatus.REPORTED,
                        ),
                    ),
                )
                attemptAccounting.record(
                    secondAttempt,
                    AttemptOutcome.Success(
                        usage = Usage(inputTokens = 10, outputTokens = 8),
                        cost = Cost(
                            usd = BigDecimal("0.002"),
                            inputUsd = BigDecimal("0.001"),
                            outputUsd = BigDecimal("0.001"),
                            status = com.example.llmgateway.domain.model.CostStatus.REPORTED,
                        ),
                    ),
                )

                val outcome = RequestOutcome(RequestOutcomeStatus.SUCCESS)
                requestAccounting.record(requestContext, request, outcome)
                requestAccounting.record(requestContext, request, outcome)

                jdbc.queryForObject(
                    "SELECT attempt_count FROM llm_gateway_request_usage WHERE request_id = ?",
                    Int::class.java,
                    "aggregate-request",
                ) shouldBe 2
                jdbc.queryForObject(
                    "SELECT fallback_count FROM llm_gateway_request_usage WHERE request_id = ?",
                    Int::class.java,
                    "aggregate-request",
                ) shouldBe 1
                jdbc.queryForObject(
                    "SELECT input_tokens + output_tokens FROM llm_gateway_request_usage WHERE request_id = ?",
                    Long::class.java,
                    "aggregate-request",
                ) shouldBe 28L
                jdbc.queryForObject(
                    "SELECT total_cost_usd FROM llm_gateway_request_usage WHERE request_id = ?",
                    BigDecimal::class.java,
                    "aggregate-request",
                ) shouldBe BigDecimal("0.003000000000")
            }

        test("PostgreSQL pricing catalog returns a versioned token price")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val transactionManager = DataSourceTransactionManager(jdbc.dataSource!!)
                val pricedDeployment = deployment.copy(
                    inputCostPer1kUsd = BigDecimal("0.001"),
                    outputCostPer1kUsd = BigDecimal("0.002"),
                    cacheReadInputCostPer1kUsd = BigDecimal("0.0002"),
                    cacheWriteInputCostPer1kUsd = BigDecimal("0.0005"),
                )
                val catalog = PostgresPricingCatalogAdapter(
                    jdbcTemplate = jdbc,
                    transactionTemplate = org.springframework.transaction.support.TransactionTemplate(transactionManager),
                    configuredDeployments = listOf(pricedDeployment),
                    clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC),
                )

                catalog.initialize()
                val snapshot = catalog.resolve(pricedDeployment, Instant.parse("2026-01-02T00:00:00Z"))

                snapshot.version.startsWith("config-") shouldBe true
                snapshot.inputCostPerTokenUsd shouldBe BigDecimal("0.000001000000000000")
                snapshot.outputCostPerTokenUsd shouldBe BigDecimal("0.000002000000000000")
                snapshot.cacheReadInputCostPerTokenUsd shouldBe BigDecimal("0.000000200000000000")
                snapshot.cacheWriteInputCostPerTokenUsd shouldBe BigDecimal("0.000000500000000000")
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
            DROP TABLE IF EXISTS llm_gateway_request_usage,
                llm_gateway_attempt_usage,
                llm_gateway_deployment_pricing,
                llm_gateway_pricing_version,
                llm_gateway_routing_version,
                llm_gateway_deployment CASCADE
            """.trimIndent(),
        )
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
                cache_read_input_cost_per_1k_usd NUMERIC(18, 8) NOT NULL DEFAULT 0,
                cache_write_input_cost_per_1k_usd NUMERIC(18, 8) NOT NULL DEFAULT 0,
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
        jdbc.execute(
            """
            CREATE TABLE llm_gateway_pricing_version (
                version VARCHAR(128) PRIMARY KEY,
                source VARCHAR(128) NOT NULL,
                effective_from TIMESTAMPTZ NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TABLE llm_gateway_deployment_pricing (
                deployment_id VARCHAR(128) NOT NULL,
                pricing_version VARCHAR(128) NOT NULL,
                input_cost_per_token_usd NUMERIC(24, 18),
                output_cost_per_token_usd NUMERIC(24, 18),
                cache_read_input_cost_per_token_usd NUMERIC(24, 18),
                cache_write_input_cost_per_token_usd NUMERIC(24, 18),
                PRIMARY KEY (deployment_id, pricing_version)
            )
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TABLE llm_gateway_attempt_usage (
                request_id VARCHAR(128) NOT NULL,
                attempt_id VARCHAR(128) NOT NULL,
                trace_id VARCHAR(128),
                attempt_sequence INTEGER NOT NULL,
                caller VARCHAR(128) NOT NULL,
                tenant VARCHAR(128) NOT NULL,
                vendor VARCHAR(64) NOT NULL,
                deployment_id VARCHAR(128) NOT NULL,
                model_group VARCHAR(128) NOT NULL,
                provider_model VARCHAR(256) NOT NULL,
                streaming BOOLEAN NOT NULL,
                started_at TIMESTAMPTZ NOT NULL,
                completed_at TIMESTAMPTZ NOT NULL,
                outcome VARCHAR(64) NOT NULL,
                failure_class VARCHAR(64),
                usage_available BOOLEAN NOT NULL,
                input_tokens BIGINT NOT NULL,
                output_tokens BIGINT NOT NULL,
                cache_read_input_tokens BIGINT NOT NULL,
                cache_write_input_tokens BIGINT NOT NULL,
                reasoning_output_tokens BIGINT NOT NULL,
                input_cost_usd NUMERIC(24, 12) NOT NULL,
                output_cost_usd NUMERIC(24, 12) NOT NULL,
                cache_read_cost_usd NUMERIC(24, 12) NOT NULL,
                cache_write_cost_usd NUMERIC(24, 12) NOT NULL,
                total_cost_usd NUMERIC(24, 12) NOT NULL,
                cost_status VARCHAR(32) NOT NULL,
                pricing_version VARCHAR(128),
                cost_warnings VARCHAR(512),
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (request_id, attempt_id)
            )
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TABLE llm_gateway_request_usage (
                request_id VARCHAR(128) PRIMARY KEY,
                trace_id VARCHAR(128),
                caller VARCHAR(128) NOT NULL,
                tenant VARCHAR(128) NOT NULL,
                model_group VARCHAR(128) NOT NULL,
                streaming BOOLEAN NOT NULL,
                started_at TIMESTAMPTZ NOT NULL,
                completed_at TIMESTAMPTZ NOT NULL,
                outcome VARCHAR(32) NOT NULL,
                error_type VARCHAR(128),
                error_code VARCHAR(128),
                attempt_count INTEGER NOT NULL,
                fallback_count INTEGER NOT NULL,
                usage_available BOOLEAN NOT NULL,
                input_tokens BIGINT NOT NULL,
                output_tokens BIGINT NOT NULL,
                cache_read_input_tokens BIGINT NOT NULL,
                cache_write_input_tokens BIGINT NOT NULL,
                reasoning_output_tokens BIGINT NOT NULL,
                input_cost_usd NUMERIC(24, 12) NOT NULL,
                output_cost_usd NUMERIC(24, 12) NOT NULL,
                cache_read_cost_usd NUMERIC(24, 12) NOT NULL,
                cache_write_cost_usd NUMERIC(24, 12) NOT NULL,
                total_cost_usd NUMERIC(24, 12) NOT NULL,
                cost_status VARCHAR(32) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
    }

    private val deployment = Deployment(
        id = DeploymentId("openai-test"),
        vendor = Vendor.OPENAI,
        dialect = Dialect.OPENAI,
        modelGroup = ModelGroup("default"),
        model = "gpt-test",
    )

    private val request = CanonicalChatRequest(
        modelGroup = ModelGroup("default"),
        messages = listOf(CanonicalMessage(MessageRole.USER, "hello")),
    )
}
