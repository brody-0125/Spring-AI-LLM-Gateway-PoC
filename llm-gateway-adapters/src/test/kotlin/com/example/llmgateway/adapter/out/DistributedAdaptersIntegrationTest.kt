package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.postgres.PostgresAttemptAccountingAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresDeploymentRegistryAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresPricingCatalogAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresRegistrySeedAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresRequestAccountingAdapter
import com.example.llmgateway.adapter.out.redis.RedisCircuitBreakerAdapter
import com.example.llmgateway.adapter.out.redis.RedisTokenBucketRateLimiter
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ExecutionId
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.error.FailureClass
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptFailure
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.execution.RequestOutcomeStatus
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.CanonicalMessage
import com.example.llmgateway.domain.policy.DeploymentOverride
import com.example.llmgateway.domain.routing.Deployment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

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

            first.inspect(deployment) shouldBe true
            val initial = first.acquire(deployment, AttemptId("initial"))!!
            val stale = first.acquire(deployment, AttemptId("stale"))!!
            first.onFailure(deployment, initial, FailureClass.TRANSIENT)
            // Duplicate delivery must not count as a second health failure.
            first.onFailure(deployment, initial, FailureClass.TRANSIENT)
            second.inspect(deployment) shouldBe true
            val failing = second.acquire(deployment, AttemptId("failing"))!!
            second.onFailure(deployment, failing, FailureClass.TRANSIENT)
            first.inspect(deployment) shouldBe false
            first.onSuccess(deployment, stale)
            first.inspect(deployment) shouldBe false
            Thread.sleep(120)
            repeat(5) {
                first.inspect(deployment) shouldBe true
                second.inspect(deployment) shouldBe true
            }
            val probe = first.acquire(deployment, AttemptId("probe"))!!
            second.acquire(deployment, AttemptId("loser")) shouldBe null
            second.inspect(deployment) shouldBe false
            first.onSuccess(deployment, stale)
            second.inspect(deployment) shouldBe false
            first.onSuccess(deployment, probe)
            second.inspect(deployment) shouldBe true
            // A completion from a previous probe generation cannot affect a new one.
            val next = first.acquire(deployment, AttemptId("next"))!!
            first.onFailure(deployment, next, FailureClass.TRANSIENT)
            val nextFailure = first.acquire(deployment, AttemptId("next-failure"))!!
            first.onFailure(deployment, nextFailure, FailureClass.TRANSIENT)
            first.onSuccess(deployment, probe)
            second.inspect(deployment) shouldBe false
        }

        test("half-open inspection leaves unselected candidates untouched and acquisition is atomic")
            .config(enabled = enabled) {
                val template = redisTemplate()
                val first = RedisCircuitBreakerAdapter(template, true, 1, Duration.ofMillis(30),
                    "test-circuit-contention", Duration.ofSeconds(1))
                val second = RedisCircuitBreakerAdapter(redisTemplate(), true, 1, Duration.ofMillis(30),
                    "test-circuit-contention", Duration.ofSeconds(1))
                val candidates = (1..3).map { deployment.copy(id = DeploymentId("candidate-$it")) }
                candidates.forEachIndexed { index, candidate ->
                    val permit = first.acquire(candidate, AttemptId("initial-$index"))!!
                    first.onFailure(candidate, permit, FailureClass.TRANSIENT)
                }
                Thread.sleep(50)
                val stateKeys = template.keys("test-circuit-contention:*").toList()
                val before = stateKeys.associateWith { template.opsForHash<String, String>().entries(it) }
                repeat(3) { candidates.forEach { first.inspect(it) shouldBe true } }
                stateKeys.associateWith { template.opsForHash<String, String>().entries(it) } shouldBe before

                val selected = candidates.first()
                val permits = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    (1..20).map { index ->
                        executor.submit<com.example.llmgateway.domain.routing.CircuitPermit?> {
                            (if (index % 2 == 0) first else second).acquire(selected, AttemptId("race-$index"))
                        }
                    }.mapNotNull { it.get() }
                }
                permits.size shouldBe 1
                candidates.drop(1).forEach { second.inspect(it) shouldBe true }
                // A missing/expired receipt is not evidence that the remote probe ended.
                Thread.sleep(1100)
                second.inspect(selected) shouldBe false
                second.acquire(selected, AttemptId("after-expiry")) shouldBe null
                first.onSuccess(selected, permits.single())
                second.inspect(selected) shouldBe false
            }

        test("PostgreSQL registry persists routing overrides across adapter instances")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val transactionManager = DataSourceTransactionManager(jdbc.dataSource!!)
                PostgresRegistrySeedAdapter(jdbc, org.springframework.transaction.support.TransactionTemplate(transactionManager))
                    .seed(listOf(deployment))
                val first = PostgresDeploymentRegistryAdapter(jdbc, transactionManager)

                first.snapshot().version shouldBe 2
                first.update(listOf(DeploymentOverride(deployment.id, enabled = false, weight = 0))).version shouldBe 3

                val second = PostgresDeploymentRegistryAdapter(jdbc, transactionManager)
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
                second.snapshot().version shouldBe 5L
            }

        test("explicit seed is serialized and does not overwrite managed deployment or pricing")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val manager = DataSourceTransactionManager(jdbc.dataSource!!)
                val initial = deployment.copy(inputCostPer1kUsd = BigDecimal("0.001"))
                val seeders = (1..2).map {
                    PostgresRegistrySeedAdapter(jdbc, org.springframework.transaction.support.TransactionTemplate(manager))
                }
                val ready = java.util.concurrent.CountDownLatch(2)
                val results = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    seeders.map { seeder ->
                        executor.submit<com.example.llmgateway.domain.policy.RegistrySeedResult> {
                            ready.countDown()
                            check(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            seeder.seed(listOf(initial))
                        }
                    }.map { it.get() }
                }
                results.sumOf { it.created } shouldBe 1
                results.sumOf { it.existing } shouldBe 1
                val registry = PostgresDeploymentRegistryAdapter(jdbc, manager)
                registry.snapshot().version shouldBe 2L
                registry.update(listOf(DeploymentOverride(deployment.id, enabled = false, priority = 7, weight = 0)))
                val before = registry.snapshot()
                val price = PostgresPricingCatalogAdapter(jdbc).resolve(initial, Instant.now())
                seeders.first().seed(listOf(initial.copy(model = "changed", priority = 0, inputCostPer1kUsd = BigDecimal("99"))))
                    .created shouldBe 0
                repeat(3) {
                    PostgresDeploymentRegistryAdapter(jdbc, manager).snapshot() shouldBe before
                    PostgresPricingCatalogAdapter(jdbc).resolve(initial, Instant.now()) shouldBe price
                }
                shouldThrow<IllegalArgumentException> { seeders.first().seed(emptyList()) }
                registry.snapshot() shouldBe before
            }

        test("seed rolls back deployment and revision when pre-existing orphan pricing requires reconciliation")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                jdbc.update("INSERT INTO llm_gateway_pricing_version (version, source, effective_from) VALUES ('managed', 'test', CURRENT_TIMESTAMP)")
                jdbc.update(
                    "INSERT INTO llm_gateway_deployment_pricing (deployment_id, pricing_version) VALUES (?, 'managed')",
                    deployment.id.value,
                )
                val manager = DataSourceTransactionManager(jdbc.dataSource!!)
                val seeder = PostgresRegistrySeedAdapter(jdbc, org.springframework.transaction.support.TransactionTemplate(manager))
                shouldThrow<IllegalStateException> { seeder.seed(listOf(deployment)) }
                jdbc.queryForObject("SELECT COUNT(*) FROM llm_gateway_deployment", Long::class.java) shouldBe 0L
                jdbc.queryForObject("SELECT COUNT(*) FROM llm_gateway_pricing_version", Long::class.java) shouldBe 1L
                jdbc.queryForObject("SELECT version FROM llm_gateway_routing_version WHERE id = 1", Long::class.java) shouldBe 1L
            }

        test("execution snapshot never mixes policy and prices from concurrent publication")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val manager = DataSourceTransactionManager(jdbc.dataSource!!)
                val transaction = org.springframework.transaction.support.TransactionTemplate(manager)
                val initial = deployment.copy(inputCostPer1kUsd = BigDecimal("1"))
                PostgresRegistrySeedAdapter(jdbc, transaction,
                    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC))
                    .seed(listOf(initial))
                val versionRead = java.util.concurrent.CountDownLatch(1)
                val published = java.util.concurrent.CountDownLatch(1)
                val reader = PostgresDeploymentRegistryAdapter(
                    SnapshotBarrierJdbcTemplate(jdbc.dataSource!!, versionRead, published), manager)
                val before = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    val reading = executor.submit<com.example.llmgateway.domain.routing.RoutingSnapshot> { reader.current() }
                    try {
                        check(versionRead.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        transaction.executeWithoutResult {
                            jdbc.update("UPDATE llm_gateway_routing_version SET version = version + 1 WHERE id = 1")
                            jdbc.update("UPDATE llm_gateway_deployment SET priority = 99, enabled = false WHERE id = ?", initial.id.value)
                            jdbc.update("""INSERT INTO llm_gateway_pricing_version (version, source, effective_from)
                                VALUES ('published-v2', 'test', '2026-01-02T00:00:00Z')""")
                            jdbc.update("""INSERT INTO llm_gateway_deployment_pricing
                                (deployment_id, pricing_version, input_cost_per_token_usd)
                                VALUES (?, 'published-v2', 100)""", initial.id.value)
                        }
                    } finally {
                        published.countDown()
                    }
                    reading.get(10, java.util.concurrent.TimeUnit.SECONDS)
                }
                before.version shouldBe 2L
                before.deployments.single().priority shouldBe initial.priority
                before.deployments.single().enabled shouldBe true
                before.pricing.getValue(initial.id).inputCostPerTokenUsd!!.compareTo(BigDecimal("0.001")) shouldBe 0
                reader.isEnabled(initial.id) shouldBe false
                val after = reader.current()
                after.version shouldBe 3L
                after.deployments.single().priority shouldBe 99
                after.pricing.getValue(initial.id).version shouldBe "published-v2"
                // New captures cannot mutate the old value.
                before.pricing.getValue(initial.id).version.startsWith("seed-") shouldBe true
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
                    executionId = ExecutionId.newId(),
                    kind = AttemptKind.INITIAL,
                    attemptId = AttemptId("accounting-attempt"),
                    sequence = 1,
                    deployment = deployment,
                    caller = "bff",
                    tenant = "tenant-a",
                    traceId = "trace-accounting",
                )
                val outcome = AttemptSuccess(
                    usage = Usage(inputTokens = 12, outputTokens = 8),
                    cost = Cost(status = com.example.llmgateway.domain.accounting.CostStatus.UNKNOWN),
                    providerRequestId = "provider-response-1",
                )

                accounting.record(context, outcome)
                accounting.record(context, outcome)

                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM llm_gateway_attempt_usage WHERE execution_id = ? AND attempt_id = ?",
                    Long::class.java,
                    context.executionId.value,
                    "accounting-attempt",
                ) shouldBe 1L
                jdbc.queryForObject(
                    "SELECT cost_status FROM llm_gateway_attempt_usage WHERE execution_id = ? AND attempt_id = ?",
                    String::class.java,
                    context.executionId.value,
                    "accounting-attempt",
                ) shouldBe "UNKNOWN"
                jdbc.queryForObject(
                    "SELECT provider_request_id FROM llm_gateway_attempt_usage WHERE execution_id = ? AND attempt_id = ?",
                    String::class.java,
                    context.executionId.value,
                    "accounting-attempt",
                ) shouldBe "provider-response-1"
            }

        test("PostgreSQL counts one initial two retries and one fallback with every attempt cost")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val transaction = org.springframework.transaction.support.TransactionTemplate(
                    DataSourceTransactionManager(jdbc.dataSource!!),
                )
                val attempts = PostgresAttemptAccountingAdapter(jdbc, transaction)
                val requests = PostgresRequestAccountingAdapter(jdbc, transaction)
                val context = RequestContext(RequestId("aggregate-request"), caller = "bff", tenant = "tenant-a")
                val kinds = listOf(AttemptKind.INITIAL, AttemptKind.RETRY, AttemptKind.RETRY, AttemptKind.FALLBACK)
                kinds.forEachIndexed { index, kind ->
                    val attempt = AttemptContext(
                        requestId = context.requestId, executionId = context.executionId,
                        attemptId = AttemptId("aggregate-$index"), sequence = index + 1, kind = kind,
                        deployment = if (kind == AttemptKind.FALLBACK) deployment.copy(id = DeploymentId("alternate")) else deployment,
                        caller = context.caller, tenant = context.tenant,
                    )
                    val amount = BigDecimal("0.001").multiply(BigDecimal(index + 1))
                    val cost = Cost(usd = amount, inputUsd = amount,
                        status = com.example.llmgateway.domain.accounting.CostStatus.REPORTED)
                    val usage = Usage(inputTokens = 10, outputTokens = index.toLong())
                    val outcome = if (index == 3) AttemptSuccess(usage, cost)
                        else AttemptFailure(FailureClass.TRANSIENT, usage, cost)
                    attempts.record(attempt, outcome)
                    attempts.record(attempt, outcome)
                }
                repeat(2) { requests.record(context, request, RequestOutcome(RequestOutcomeStatus.SUCCESS)) }
                val row = jdbc.queryForMap(
                    "SELECT * FROM llm_gateway_request_usage WHERE execution_id = ?", context.executionId.value,
                )
                row["correlation_id"] shouldBe context.requestId.value
                row["attempt_count"] shouldBe 4
                row["initial_count"] shouldBe 1
                row["retry_count"] shouldBe 2
                row["fallback_count"] shouldBe 1
                row["input_tokens"] shouldBe 40L
                row["output_tokens"] shouldBe 6L
                row["total_cost_usd"] shouldBe BigDecimal("0.010000000000000000")
                shouldThrow<IllegalStateException> {
                    requests.record(context.copy(tenant = "another-tenant"), request, RequestOutcome(RequestOutcomeStatus.SUCCESS))
                }
                jdbc.queryForObject(
                    "SELECT tenant FROM llm_gateway_request_usage WHERE execution_id = ?",
                    String::class.java, context.executionId.value,
                ) shouldBe context.tenant
            }

        test("same correlation concurrent requests cannot merge usage across executions or tenants")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val transaction = org.springframework.transaction.support.TransactionTemplate(
                    DataSourceTransactionManager(jdbc.dataSource!!),
                )
                val attempts = PostgresAttemptAccountingAdapter(jdbc, transaction)
                val requests = PostgresRequestAccountingAdapter(jdbc, transaction)
                val contexts = (1..20).map {
                    RequestContext(RequestId("shared-correlation"), caller = "bff", tenant = "tenant-${it % 2}")
                }
                val ready = java.util.concurrent.CountDownLatch(contexts.size)
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    contexts.mapIndexed { index, context ->
                        executor.submit {
                            ready.countDown()
                            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            val attempt = AttemptContext(
                                requestId = context.requestId, executionId = context.executionId,
                                attemptId = AttemptId("concurrent-$index"), sequence = 1, kind = AttemptKind.INITIAL,
                                deployment = deployment, caller = context.caller, tenant = context.tenant,
                            )
                            attempts.record(attempt, AttemptSuccess(
                                Usage(inputTokens = (index + 1).toLong()), Cost(),
                            ))
                            requests.record(context, request, RequestOutcome(RequestOutcomeStatus.SUCCESS))
                        }
                    }.forEach { it.get() }
                }
                jdbc.queryForObject("SELECT COUNT(*) FROM llm_gateway_request_usage", Long::class.java) shouldBe 20L
                contexts.forEachIndexed { index, context ->
                    val row = jdbc.queryForMap(
                        "SELECT * FROM llm_gateway_request_usage WHERE execution_id = ? AND tenant = ? AND caller = ?",
                        context.executionId.value, context.tenant, context.caller,
                    )
                    row["input_tokens"] shouldBe (index + 1).toLong()
                    row["attempt_count"] shouldBe 1
                    row["correlation_id"] shouldBe "shared-correlation"
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM llm_gateway_request_usage WHERE execution_id = ? AND tenant <> ?",
                        Long::class.java, context.executionId.value, context.tenant,
                    ) shouldBe 0L
                }
            }

        test("V6 migration preserves legacy rows without inventing execution identity or attempt counts")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc, "5")
                jdbc.update(
                    """
                    INSERT INTO llm_gateway_request_usage (
                        request_id, caller, tenant, model_group, streaming, started_at, completed_at, outcome,
                        attempt_count, fallback_count, usage_available,
                        input_tokens, output_tokens, cache_read_input_tokens, cache_write_input_tokens,
                        reasoning_output_tokens, input_cost_usd, output_cost_usd, cache_read_cost_usd,
                        cache_write_cost_usd, total_cost_usd, cost_status
                    ) VALUES ('legacy-correlation', 'bff', 'tenant-a', 'default', false, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, 'success', 2, 1, true, 20, 8, 0, 0, 0, 0.001, 0.002, 0, 0, 0.003, 'REPORTED')
                    """.trimIndent(),
                )
                val schema = jdbc.queryForObject("SELECT current_schema()", String::class.java)!!
                org.flywaydb.core.Flyway.configure().dataSource(jdbc.dataSource!!)
                    .defaultSchema(schema).schemas(schema).locations("classpath:db/migration").load().migrate()
                val legacy = jdbc.queryForMap("SELECT * FROM llm_gateway_request_usage WHERE request_id = 'legacy-correlation'")
                legacy["execution_id"] shouldBe null
                legacy["correlation_id"] shouldBe null
                legacy["initial_count"] shouldBe null
                legacy["retry_count"] shouldBe null
                legacy["fallback_count"] shouldBe 1
                legacy["total_cost_usd"] shouldBe BigDecimal("0.003000000000000000")
            }

        test("price migration preserves historical rate cards and new seed distinguishes unknown from free")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc, targetVersion = "6")
                jdbc.update("""INSERT INTO llm_gateway_deployment
                    (id, vendor, dialect, model_group, model, enabled, weight, supports_streaming,
                     input_cost_per_1k_usd, output_cost_per_1k_usd)
                    VALUES ('legacy', 'OPENAI', 'OPENAI', 'default', 'test', true, 1, true, 0, 0.002)""")
                jdbc.update("""INSERT INTO llm_gateway_pricing_version (version, source, effective_from)
                    VALUES ('legacy-v1', 'managed', CURRENT_TIMESTAMP)""")
                jdbc.update("""INSERT INTO llm_gateway_deployment_pricing
                    (deployment_id, pricing_version, input_cost_per_token_usd, output_cost_per_token_usd)
                    VALUES ('legacy', 'legacy-v1', 0, NULL)""")
                val dataSource = jdbc.dataSource as DriverManagerDataSource
                org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                    .schemas(dataSource.schema).defaultSchema(dataSource.schema)
                    .locations("classpath:db/migration").load().migrate()
                val legacy = jdbc.queryForMap("SELECT * FROM llm_gateway_deployment WHERE id = 'legacy'")
                legacy["input_cost_per_1k_usd"] shouldBe null
                legacy["output_cost_per_1k_usd"] shouldBe BigDecimal("0.002000000000000")
                val oldPrice = jdbc.queryForMap("SELECT * FROM llm_gateway_deployment_pricing WHERE deployment_id = 'legacy'")
                oldPrice["input_cost_per_token_usd"] shouldBe BigDecimal.ZERO.setScale(18)
                oldPrice["output_cost_per_token_usd"] shouldBe null

                val seed = PostgresRegistrySeedAdapter(jdbc,
                    org.springframework.transaction.support.TransactionTemplate(DataSourceTransactionManager(dataSource)))
                val unknown = deployment.copy(id = DeploymentId("unknown-price"))
                val free = deployment.copy(id = DeploymentId("free-price"),
                    inputCostPer1kUsd = BigDecimal.ZERO, outputCostPer1kUsd = BigDecimal.ZERO,
                    cacheReadInputCostPer1kUsd = BigDecimal.ZERO, cacheWriteInputCostPer1kUsd = BigDecimal.ZERO)
                val precise = deployment.copy(id = DeploymentId("precise-price"),
                    inputCostPer1kUsd = BigDecimal("0.000000000000001"))
                seed.seed(listOf(unknown, free, precise)).created shouldBe 3
                val catalog = PostgresPricingCatalogAdapter(jdbc)
                catalog.resolve(unknown, Instant.now()).inputCostPerTokenUsd shouldBe null
                catalog.resolve(free, Instant.now()).inputCostPerTokenUsd shouldBe BigDecimal.ZERO.setScale(18)
                catalog.resolve(precise, Instant.now()).inputCostPerTokenUsd shouldBe BigDecimal("0.000000000000000001")
                val tinyUsage = Usage(inputTokens = 1)
                val tinyCost = com.example.llmgateway.domain.accounting.CostCalculator()
                    .calculate(tinyUsage, catalog.resolve(precise, Instant.now()))
                val tinyContext = RequestContext(RequestId("tiny-charge"), caller = "bff", tenant = "test")
                val accountingTransaction = org.springframework.transaction.support.TransactionTemplate(
                    DataSourceTransactionManager(dataSource))
                val accounting = PostgresAttemptAccountingAdapter(jdbc, accountingTransaction)
                (1..2).forEach { sequence ->
                    val attempt = AttemptContext(tinyContext.requestId, AttemptId("tiny-$sequence"), sequence, precise,
                        tinyContext.executionId, if (sequence == 1) AttemptKind.INITIAL else AttemptKind.RETRY,
                        caller = tinyContext.caller, tenant = tinyContext.tenant)
                    repeat(2) { accounting.record(attempt, AttemptSuccess(tinyUsage, tinyCost)) }
                }
                jdbc.queryForObject("SELECT SUM(total_cost_usd) FROM llm_gateway_attempt_usage WHERE execution_id = ?",
                    BigDecimal::class.java, tinyContext.executionId.value) shouldBe BigDecimal("0.000000000000000002")
                PostgresRequestAccountingAdapter(jdbc, accountingTransaction).record(tinyContext,
                    CanonicalChatRequest(precise.modelGroup, emptyList()), RequestOutcome(RequestOutcomeStatus.SUCCESS))
                jdbc.queryForObject("SELECT total_cost_usd FROM llm_gateway_request_usage WHERE execution_id = ?",
                    BigDecimal::class.java, tinyContext.executionId.value) shouldBe BigDecimal("0.000000000000000002")
                PostgresDeploymentRegistryAdapter(jdbc, DataSourceTransactionManager(dataSource))
                    .snapshot().deployments.single { it.id == precise.id }.inputCostPer1kUsd shouldBe
                    BigDecimal("0.000000000000001")
                seed.seed(listOf(free.copy(inputCostPer1kUsd = BigDecimal.ONE))).existing shouldBe 1
                catalog.resolve(free, Instant.now()).inputCostPerTokenUsd shouldBe BigDecimal.ZERO.setScale(18)
                shouldThrow<ArithmeticException> {
                    seed.seed(listOf(deployment.copy(id = DeploymentId("rounded-price"),
                        inputCostPer1kUsd = BigDecimal("0.0000000000000001"))))
                }
                jdbc.queryForObject("SELECT COUNT(*) FROM llm_gateway_deployment WHERE id = 'rounded-price'",
                    Long::class.java) shouldBe 0L
            }

        test("normalized provider usage and rate-card source survive attempt and request accounting")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val transaction = org.springframework.transaction.support.TransactionTemplate(
                    DataSourceTransactionManager(jdbc.dataSource!!))
                val selected = deployment.copy(vendor = Vendor.AWS_BEDROCK, dialect = Dialect.BEDROCK_CONVERSE,
                    inputCostPer1kUsd = BigDecimal("1"), outputCostPer1kUsd = BigDecimal("2"),
                    cacheReadInputCostPer1kUsd = BigDecimal("0.1"), cacheWriteInputCostPer1kUsd = BigDecimal("0.5"))
                PostgresRegistrySeedAdapter(jdbc, transaction).seed(listOf(selected))
                val native = software.amazon.awssdk.services.bedrockruntime.model.TokenUsage.builder()
                    .inputTokens(10).outputTokens(5).totalTokens(15)
                    .cacheReadInputTokens(100).cacheWriteInputTokens(20).build()
                val provider = com.example.llmgateway.adapter.out.springai.ChatResponseMapper.toProviderResponse(
                    org.springframework.ai.chat.model.ChatResponse(emptyList(),
                        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                            .usage(org.springframework.ai.chat.metadata.DefaultUsage(10, 5, 15, native, 100L, 20L)).build()),
                    Vendor.AWS_BEDROCK)
                val cost = com.example.llmgateway.domain.accounting.CostCalculator().calculate(provider.usage,
                    PostgresPricingCatalogAdapter(jdbc).resolve(selected, Instant.now()))
                val context = RequestContext(RequestId("rate-card-test"), caller = "bff", tenant = "test")
                val attempt = AttemptContext(context.requestId, AttemptId("rate-card-attempt"), 1, selected,
                    context.executionId, AttemptKind.INITIAL, caller = context.caller, tenant = context.tenant)
                val accounting = PostgresAttemptAccountingAdapter(jdbc, transaction)
                repeat(2) { accounting.record(attempt, AttemptSuccess(provider.usage, cost)) }
                val row = jdbc.queryForMap("SELECT * FROM llm_gateway_attempt_usage WHERE execution_id = ?",
                    context.executionId.value)
                row["input_tokens"] shouldBe 130L
                row["input_cost_usd"] shouldBe BigDecimal("0.010000000000000000")
                row["total_cost_usd"] shouldBe BigDecimal("0.040000000000000000")
                row["cost_status"] shouldBe "ESTIMATED"
                row["cost_source"] shouldBe "RATE_CARD"
                PostgresRequestAccountingAdapter(jdbc, transaction).record(context,
                    CanonicalChatRequest(selected.modelGroup, emptyList()), RequestOutcome(RequestOutcomeStatus.SUCCESS))
                val request = jdbc.queryForMap("SELECT * FROM llm_gateway_request_usage WHERE execution_id = ?",
                    context.executionId.value)
                request["attempt_count"] shouldBe 1
                request["cost_status"] shouldBe "ESTIMATED"
                request["total_cost_usd"] shouldBe row["total_cost_usd"]
            }

        test("typed component prices and lines are atomic idempotent and retain unknown quantities")
            .config(enabled = enabled) {
                val jdbc = postgresJdbcTemplate()
                createSchema(jdbc)
                val transaction = org.springframework.transaction.support.TransactionTemplate(
                    DataSourceTransactionManager(jdbc.dataSource!!))
                val selected = deployment.copy(inputCostPer1kUsd = BigDecimal("1"),
                    outputCostPer1kUsd = BigDecimal("2"), cacheReadInputCostPer1kUsd = BigDecimal("0.1"),
                    cacheWriteInputCostPer1kUsd = BigDecimal("0.5"))
                PostgresRegistrySeedAdapter(jdbc, transaction).seed(listOf(selected))
                val catalog = PostgresPricingCatalogAdapter(jdbc)
                val base = catalog.resolve(selected, Instant.now())
                listOf(
                    listOf("RERANK_QUERIES", "", "QUERY", "0.01"),
                    listOf("RERANK_DOCUMENT_BLOCKS", "", "DOCUMENT_BLOCK", "0.003"),
                    listOf("TOOL_INVOCATIONS", "search", "INVOCATION", "0.1"),
                ).forEach { line ->
                    jdbc.update("""INSERT INTO llm_gateway_component_pricing
                        (deployment_id, pricing_version, usage_type, variant, unit, usd_per_unit)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                        selected.id.value, base.version, line[0], line[1], line[2], BigDecimal(line[3]))
                }
                val snapshot = catalog.resolve(selected, Instant.now())
                snapshot.prices.size shouldBe 7
                val usage = Usage(Usage(inputTokens = 100, outputTokens = 40,
                    cacheReadInputTokens = 20, cacheWriteInputTokens = 10, reasoningOutputTokens = 30).components + listOf(
                    com.example.llmgateway.domain.accounting.UsageComponent(com.example.llmgateway.domain.accounting.UsageKey(
                        com.example.llmgateway.domain.accounting.UsageType.RERANK_QUERIES), 3),
                    com.example.llmgateway.domain.accounting.UsageComponent(com.example.llmgateway.domain.accounting.UsageKey(
                        com.example.llmgateway.domain.accounting.UsageType.RERANK_DOCUMENT_BLOCKS), 2),
                    com.example.llmgateway.domain.accounting.UsageComponent(com.example.llmgateway.domain.accounting.UsageKey(
                        com.example.llmgateway.domain.accounting.UsageType.TOOL_INVOCATIONS, "search"), null),
                ))
                val cost = com.example.llmgateway.domain.accounting.CostCalculator().calculate(usage, snapshot)
                val context = RequestContext(RequestId("components"), caller = "bff", tenant = "test")
                val attempt = AttemptContext(context.requestId, AttemptId("components"), 1, selected,
                    context.executionId, AttemptKind.INITIAL, caller = context.caller, tenant = context.tenant)
                val accounting = PostgresAttemptAccountingAdapter(jdbc, transaction)
                repeat(2) { accounting.record(attempt, AttemptSuccess(usage, cost)) }
                jdbc.queryForObject("SELECT COUNT(*) FROM llm_gateway_usage_component", Long::class.java) shouldBe 8L
                jdbc.queryForObject("SELECT component_schema_version FROM llm_gateway_attempt_usage",
                    Int::class.java) shouldBe 1
                jdbc.queryForObject("SELECT COUNT(*) FROM llm_gateway_cost_line", Long::class.java) shouldBe 7L
                val unknown = jdbc.queryForMap("SELECT * FROM llm_gateway_usage_component WHERE usage_type = 'TOOL_INVOCATIONS'")
                unknown["quantity"] shouldBe null
                unknown["measurement_source"] shouldBe "UNKNOWN"
                unknown["variant"] shouldBe "search"
                val unknownCost = jdbc.queryForMap("SELECT * FROM llm_gateway_cost_line WHERE usage_type = 'TOOL_INVOCATIONS'")
                unknownCost["amount_usd"] shouldBe null
                unknownCost["cost_status"] shouldBe "UNKNOWN"
                jdbc.queryForObject("SELECT SUM(amount_usd) FROM llm_gateway_cost_line",
                    BigDecimal::class.java) shouldBe BigDecimal("0.193000000000000000")
                jdbc.queryForObject("SELECT total_cost_usd FROM llm_gateway_attempt_usage",
                    BigDecimal::class.java) shouldBe cost.usd
                PostgresRequestAccountingAdapter(jdbc, transaction).record(context,
                    CanonicalChatRequest(selected.modelGroup, emptyList()), RequestOutcome(RequestOutcomeStatus.SUCCESS))
                jdbc.queryForObject("SELECT total_cost_usd FROM llm_gateway_request_usage",
                    BigDecimal::class.java) shouldBe cost.usd

                jdbc.execute("ALTER TABLE llm_gateway_usage_component ADD CONSTRAINT fixture_reject_quantity CHECK (quantity <> 999)")
                val failedAttempt = attempt.copy(attemptId = AttemptId("rollback"), sequence = 2, kind = AttemptKind.RETRY)
                val invalidUsage = Usage(inputTokens = 999)
                shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
                    accounting.record(failedAttempt, AttemptSuccess(invalidUsage,
                        com.example.llmgateway.domain.accounting.CostCalculator().calculate(invalidUsage, snapshot)))
                }
                jdbc.queryForObject("SELECT COUNT(*) FROM llm_gateway_attempt_usage WHERE attempt_id = 'rollback'",
                    Long::class.java) shouldBe 0L
                jdbc.queryForObject("SELECT COUNT(*) FROM llm_gateway_usage_component WHERE attempt_id = 'rollback'",
                    Long::class.java) shouldBe 0L
                jdbc.queryForObject("SELECT COUNT(*) FROM llm_gateway_cost_line WHERE attempt_id = 'rollback'",
                    Long::class.java) shouldBe 0L
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
                PostgresRegistrySeedAdapter(
                    jdbcTemplate = jdbc,
                    transaction = org.springframework.transaction.support.TransactionTemplate(transactionManager),
                    clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC),
                ).seed(listOf(pricedDeployment))
                val catalog = PostgresPricingCatalogAdapter(jdbc)
                val snapshot = catalog.resolve(pricedDeployment, Instant.parse("2026-01-02T00:00:00Z"))

                snapshot.version.startsWith("seed-") shouldBe true
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

    private fun createSchema(jdbc: JdbcTemplate, targetVersion: String? = null) {
        val dataSource = jdbc.dataSource as DriverManagerDataSource
        val schema = "test_" + java.util.UUID.randomUUID().toString().replace("-", "")
        org.flywaydb.core.Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .apply { targetVersion?.let { target(it) } }
            .load()
            .migrate()
        dataSource.schema = schema
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
