package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.postgres.PostgresAttemptJournalAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresExecutionJournalAdapter
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.execution.RequestOutcomeStatus
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.CanonicalMessage
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ExecutionId
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.accounting.CostCalculator
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.AttemptSuccess
import com.example.llmgateway.domain.routing.CircuitPermit
import com.example.llmgateway.domain.routing.Deployment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.flywaydb.core.Flyway
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/** Actual PostgreSQL and application Flyway migrations; no in-memory SQL substitute. */
class PostgresAttemptJournalIntegrationTest : FunSpec() {
    private val enabled = System.getenv("RUN_TESTCONTAINERS") == "true"
    private val pools = mutableListOf<com.zaxxer.hikari.HikariDataSource>()
    private val postgres = GenericContainer<Nothing>("postgres:16-alpine").apply {
        withEnv("POSTGRES_DB", "journal_fixture")
        withEnv("POSTGRES_USER", "fixture")
        withEnv("POSTGRES_PASSWORD", "fixture")
        withExposedPorts(5432)
        waitingFor(Wait.forListeningPort())
    }
    private val price = PricingSnapshot("price-v1", BigDecimal("0.001"), BigDecimal("0.002"), BigDecimal.ZERO, BigDecimal.ZERO)
    private val usage = Usage(inputTokens = 10, outputTokens = 2)
    private val outcome = AttemptSuccess(usage, CostCalculator().calculate(usage, price), "provider-fixture")

    init {
        beforeSpec { if (enabled) postgres.start() }
        afterSpec { pools.forEach { it.close() }; if (enabled) postgres.stop() }

        test("execution completion stores one receipt and outbox without synchronous projection or another settlement")
            .config(enabled = enabled) {
                val jdbc = database()
                val context = context()
                val journal = adapter(jdbc)
                journal.prepare(context, price)
                jdbc.queryForObject("SELECT state FROM llm_gateway_execution_journal", String::class.java) shouldBe "OPEN"
                jdbc.queryForObject("SELECT project_id FROM llm_gateway_execution_journal", String::class.java) shouldBe "project-owned"
                journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                journal.record(context, outcome)
                repeat(2) { executionWriter(jdbc).record(requestContext(context), request(context), RequestOutcome(RequestOutcomeStatus.SUCCESS)) }
                count(jdbc, "llm_gateway_accounting_outbox") shouldBe 2L
                count(jdbc, "llm_gateway_request_usage") shouldBe 0L
                money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "0.014"
                jdbc.queryForObject("SELECT outcome FROM llm_gateway_execution_journal", String::class.java) shouldBe "SUCCESS"
                shouldThrow<IllegalStateException> {
                    executionWriter(jdbc).record(requestContext(context), request(context), RequestOutcome(RequestOutcomeStatus.CANCELLED))
                }
                shouldThrow<IllegalStateException> {
                    journal.prepare(context.copy(attemptId = AttemptId("late-attempt"), sequence = 2), price)
                }
                count(jdbc, "llm_gateway_attempt_journal") shouldBe 1L
            }

        test("execution event failure rolls back only request terminal not the committed provider settlement")
            .config(enabled = enabled) {
                val jdbc = database()
                val context = context()
                adapter(jdbc).apply {
                    prepare(context, price)
                    dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                    record(context, outcome)
                }
                jdbc.execute("ALTER TABLE llm_gateway_accounting_outbox ADD CONSTRAINT fixture_reject_execution CHECK (execution_id IS NULL) NOT VALID")
                shouldThrow<DataIntegrityViolationException> {
                    executionWriter(jdbc).record(requestContext(context), request(context), RequestOutcome(RequestOutcomeStatus.SUCCESS))
                }
                jdbc.queryForObject("SELECT state FROM llm_gateway_execution_journal", String::class.java) shouldBe "OPEN"
                state(jdbc) shouldBe "RECORDED"
                money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "0.014"
                count(jdbc, "llm_gateway_accounting_outbox") shouldBe 1L
                jdbc.execute("ALTER TABLE llm_gateway_accounting_outbox DROP CONSTRAINT fixture_reject_execution")
                executionWriter(jdbc).record(requestContext(context), request(context), RequestOutcome(RequestOutcomeStatus.SUCCESS))
                count(jdbc, "llm_gateway_accounting_outbox") shouldBe 2L
            }

        test("an execution with no dispatched attempt can record a denial but never a success")
            .config(enabled = enabled) {
                val jdbc = database()
                val context = context()
                val writer = executionWriter(jdbc)
                shouldThrow<IllegalStateException> {
                    writer.record(requestContext(context), request(context), RequestOutcome(RequestOutcomeStatus.SUCCESS))
                }
                count(jdbc, "llm_gateway_execution_journal") shouldBe 0L
                writer.record(requestContext(context), request(context), RequestOutcome(RequestOutcomeStatus.FAILURE, "budget_exceeded", "BUDGET_EXCEEDED"))
                count(jdbc, "llm_gateway_execution_journal") shouldBe 1L
                count(jdbc, "llm_gateway_attempt_journal") shouldBe 0L
                count(jdbc, "llm_gateway_budget_reservation") shouldBe 0L
                jdbc.queryForObject("SELECT project_id FROM llm_gateway_execution_journal", String::class.java) shouldBe null
            }

        test("execution success cannot hide a pending dispatch or a changed correlation owner")
            .config(enabled = enabled) {
                val jdbc = database()
                val context = context()
                adapter(jdbc).apply {
                    prepare(context, price)
                    dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                }
                val writer = executionWriter(jdbc)
                shouldThrow<IllegalStateException> {
                    writer.record(requestContext(context), request(context), RequestOutcome(RequestOutcomeStatus.SUCCESS))
                }
                shouldThrow<EmptyResultDataAccessException> {
                    writer.record(requestContext(context).copy(caller = "foreign"), request(context), RequestOutcome(RequestOutcomeStatus.CANCELLED))
                }
                writer.record(requestContext(context), request(context), RequestOutcome(RequestOutcomeStatus.CANCELLED))
                state(jdbc) shouldBe "DISPATCH_INTENT"
                money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "30"
            }

        test("reservation failure cannot leave a new execution root committed") .config(enabled = enabled) {
            val jdbc = database()
            jdbc.update("UPDATE llm_gateway_service_budget SET limit_usd = 10")
            shouldThrow<com.example.llmgateway.domain.error.GatewayException> { adapter(jdbc).prepare(context(), price) }
            count(jdbc, "llm_gateway_execution_journal") shouldBe 0L
            count(jdbc, "llm_gateway_attempt_journal") shouldBe 0L
        }

        test("a fallback cannot change the execution service budget identity") .config(enabled = enabled) {
            val jdbc = database()
            val context = context()
            val journal = adapter(jdbc)
            journal.prepare(context, price)
            journal.abandon(context)
            jdbc.update("INSERT INTO llm_gateway_service_budget (grant_id, service_id, limit_usd) VALUES ('grant-fixture', 'other-service', 100)")
            jdbc.update("UPDATE llm_gateway_budget_binding SET service_id = 'other-service'")
            shouldThrow<IllegalStateException> {
                journal.prepare(context.copy(attemptId = AttemptId("fallback"), sequence = 2), price)
            }
            count(jdbc, "llm_gateway_attempt_journal") shouldBe 1L
            money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "0"
            jdbc.queryForObject("SELECT service_id FROM llm_gateway_execution_journal", String::class.java) shouldBe "service-owned"
        }

        test("journal LOCAL timeouts do not leak through the pooled connection").config(enabled = enabled) {
            val jdbc = database()
            val transactions = com.example.llmgateway.adapter.out.postgres.PostgresJournalTransactions(
                jdbc, DataSourceTransactionManager(jdbc.dataSource!!), poolWait(jdbc))
            var backend: Int? = null
            transactions.write(Instant.now().plusSeconds(10)) {
                jdbc.queryForObject("SHOW statement_timeout", String::class.java) shouldBe "2s"
                jdbc.queryForObject("SHOW lock_timeout", String::class.java) shouldBe "500ms"
                backend = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)
            }
            jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java) shouldBe backend
            jdbc.queryForObject("SHOW statement_timeout", String::class.java) shouldBe "0"
            jdbc.queryForObject("SHOW lock_timeout", String::class.java) shouldBe "0"
        }

        test("locked budget row times out without preparing or reserving").config(enabled = enabled) {
            val jdbc = database()
            jdbc.dataSource!!.connection.use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().use { it.executeUpdate("UPDATE llm_gateway_budget_grant SET face_usd = face_usd") }
                try {
                    val failure = shouldThrow<org.springframework.dao.DataAccessException> {
                        adapter(jdbc).prepare(context(), price)
                    }
                    generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<java.sql.SQLException>()
                        .any { it.sqlState == "55P03" } shouldBe true
                } finally { blocker.rollback() }
            }
            count(jdbc, "llm_gateway_attempt_journal") shouldBe 0L
            money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "0"
        }

        test("lost terminal commit acknowledgement rechecks the same receipt without another debit")
            .config(enabled = enabled) {
                val jdbc = database()
                val delegate = DataSourceTransactionManager(jdbc.dataSource!!)
                var loseAck = false
                var terminalCommits = 0
                val manager = object : org.springframework.transaction.PlatformTransactionManager {
                    override fun getTransaction(definition: org.springframework.transaction.TransactionDefinition?) = delegate.getTransaction(definition)
                    override fun rollback(status: org.springframework.transaction.TransactionStatus) = delegate.rollback(status)
                    override fun commit(status: org.springframework.transaction.TransactionStatus) {
                        val owner = status.isNewTransaction
                        delegate.commit(status)
                        if (owner && loseAck) {
                            loseAck = false
                            terminalCommits++
                            throw org.springframework.transaction.TransactionSystemException("Injected lost commit acknowledgement", java.sql.SQLException("fixture", "08006"))
                        }
                    }
                }
                val journal = PostgresAttemptJournalAdapter(jdbc, manager, poolWait(jdbc))
                val context = context()
                journal.prepare(context, price)
                journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                loseAck = true
                journal.record(context, outcome)
                terminalCommits shouldBe 1
                count(jdbc, "llm_gateway_attempt_usage") shouldBe 1L
                count(jdbc, "llm_gateway_accounting_outbox") shouldBe 1L
                money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "0.014"
                money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "0"
            }

        test("SQL serialization failure rolls back settlement before retrying the same terminal")
            .config(enabled = enabled) {
                val jdbc = database()
                jdbc.execute("CREATE SEQUENCE fixture_terminal_calls")
                jdbc.execute("""CREATE FUNCTION fixture_fail_once() RETURNS trigger LANGUAGE plpgsql AS
                    'BEGIN IF nextval(''fixture_terminal_calls'') = 1 THEN
                    RAISE EXCEPTION ''serialization fixture'' USING ERRCODE = ''40001'';
                    END IF; RETURN NEW; END;'""")
                jdbc.execute("CREATE TRIGGER fixture_retry BEFORE INSERT ON llm_gateway_accounting_outbox FOR EACH ROW EXECUTE FUNCTION fixture_fail_once()")
                val journal = adapter(jdbc)
                val context = context()
                journal.prepare(context, price)
                journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                journal.record(context, outcome)
                jdbc.queryForObject("SELECT last_value FROM fixture_terminal_calls", Long::class.java) shouldBe 2L
                count(jdbc, "llm_gateway_accounting_outbox") shouldBe 1L
                money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "0.014"
                money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "0"
            }

        test("100 concurrent attempts can reserve at most three 30 USD holds from 100 USD")
            .config(enabled = enabled) {
                val jdbc = database()
                val ready = java.util.concurrent.CountDownLatch(1)
                val admitted = java.util.concurrent.atomic.AtomicInteger()
                Executors.newFixedThreadPool(12).use { executor ->
                    val futures = (1..100).map {
                        executor.submit {
                            ready.await(10, TimeUnit.SECONDS)
                            val context = context()
                            val journal = adapter(jdbc)
                            try {
                                journal.prepare(context, price)
                            } catch (denied: com.example.llmgateway.domain.error.GatewayException) {
                                denied.error.code shouldBe "BUDGET_EXCEEDED"
                                return@submit
                            }
                            journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                            admitted.incrementAndGet() // Simulated provider boundary; no external calls.
                        }
                    }
                    ready.countDown()
                    futures.forEach { it.get(30, TimeUnit.SECONDS) }
                }
                admitted.get() shouldBe 3
                count(jdbc, "llm_gateway_attempt_journal") shouldBe 3L
                count(jdbc, "llm_gateway_budget_reservation") shouldBe 3L
                money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "90"
                money(jdbc, "SELECT held_usd FROM llm_gateway_service_budget") shouldBe "90"
            }

        test("service denial rolls back prepare and all grant changes") .config(enabled = enabled) {
            val jdbc = database()
            jdbc.update("UPDATE llm_gateway_service_budget SET limit_usd = 10")
            val denied = shouldThrow<com.example.llmgateway.domain.error.GatewayException> {
                adapter(jdbc).prepare(context(), price)
            }
            denied.error.code shouldBe "BUDGET_EXCEEDED"
            count(jdbc, "llm_gateway_attempt_journal") shouldBe 0L
            count(jdbc, "llm_gateway_budget_reservation") shouldBe 0L
            money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "0"
        }

        test("missing binding profile or price cannot prepare an unfunded attempt").config(enabled = enabled) {
            val jdbc = database()
            shouldThrow<EmptyResultDataAccessException> { adapter(jdbc).prepare(context().copy(caller = "unbound"), price) }
            shouldThrow<IllegalArgumentException> { adapter(jdbc).prepare(context(), null) }
            jdbc.update("UPDATE llm_gateway_chat_budget_profile SET enabled = FALSE")
            shouldThrow<EmptyResultDataAccessException> { adapter(jdbc).prepare(context(), price) }
            count(jdbc, "llm_gateway_attempt_journal") shouldBe 0L
            money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "0"
        }

        test("unknown completion keeps full hold while known completion settles exactly once")
            .config(enabled = enabled) {
                val jdbc = database()
                val journal = adapter(jdbc)
                val unknown = context()
                journal.prepare(unknown, price)
                journal.dispatch(unknown, CircuitPermit(unknown.attemptId, "generation-1"))
                journal.record(unknown, AttemptSuccess(Usage(available = false)))
                journal.record(unknown, AttemptSuccess(Usage(available = false)))
                money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "30"
                jdbc.queryForObject("SELECT state FROM llm_gateway_budget_reservation", String::class.java) shouldBe "REVIEW_REQUIRED"
                val completed = context()
                journal.prepare(completed, price)
                journal.dispatch(completed, CircuitPermit(completed.attemptId, "generation-1"))
                journal.record(completed, outcome)
                journal.record(completed, outcome)
                money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "30"
                money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "0.014"
                money(jdbc, "SELECT held_usd FROM llm_gateway_service_budget") shouldBe "30"
            }

        test("estimated usage keeps its reservation for reconciliation").config(enabled = enabled) {
            val jdbc = database()
            val journal = adapter(jdbc)
            val context = context()
            val estimate = Usage(usage.components.map { it.copy(source = com.example.llmgateway.domain.accounting.UsageSource.ESTIMATED) })
            journal.prepare(context, price)
            journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
            journal.record(context, AttemptSuccess(estimate, CostCalculator().calculate(estimate, price)))
            money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "30"
            money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "0"
            jdbc.queryForObject("SELECT state FROM llm_gateway_budget_reservation", String::class.java) shouldBe "REVIEW_REQUIRED"
        }

        test("mismatched cost cannot override the frozen reservation price").config(enabled = enabled) {
            val jdbc = database()
            val journal = adapter(jdbc)
            val context = context()
            journal.prepare(context, price)
            journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
            val incorrect = com.example.llmgateway.domain.accounting.Cost(
                usd = BigDecimal.ONE, status = com.example.llmgateway.domain.accounting.CostStatus.ESTIMATED,
                source = com.example.llmgateway.domain.accounting.CostSource.RATE_CARD, pricingVersion = price.version,
            )
            shouldThrow<IllegalStateException> { journal.record(context, AttemptSuccess(usage, incorrect)) }
            state(jdbc) shouldBe "DISPATCH_INTENT"
            count(jdbc, "llm_gateway_attempt_usage") shouldBe 0L
            money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "30"
            journal.record(context, outcome)
            money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "0.014"
        }

        test("month boundary and profile edit preserve the original request period and price")
            .config(enabled = enabled) {
                val jdbc = database()
                val journal = adapter(jdbc)
                val context = context().copy(
                    startedAt = Instant.parse("2026-10-01T00:00:01Z"),
                    deadline = Instant.parse("2026-10-01T00:01:01Z"),
                    requestDeadline = Instant.parse("2026-10-01T00:01:01Z"),
                    budgetAt = Instant.parse("2026-09-30T23:59:59Z"),
                )
                journal.prepare(context, price)
                jdbc.update("UPDATE llm_gateway_chat_budget_profile SET revision = 'next', max_billable_input_tokens = 1")
                journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                journal.record(context, outcome)
                jdbc.queryForObject("SELECT period_end FROM llm_gateway_budget_reservation", java.sql.Timestamp::class.java)
                    ?.toInstant() shouldBe Instant.parse("2026-10-01T00:00:00Z")
                jdbc.queryForObject("SELECT profile_revision FROM llm_gateway_budget_reservation", String::class.java) shouldBe "fixture-v1"
                money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "0.014"
            }

        test("provider excess charge preserves debit and freezes further admission").config(enabled = enabled) {
            val jdbc = database()
            val journal = adapter(jdbc)
            val context = context()
            journal.prepare(context, price)
            journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
            val over = com.example.llmgateway.domain.accounting.Cost(
                usd = BigDecimal("110"), status = com.example.llmgateway.domain.accounting.CostStatus.REPORTED,
                source = com.example.llmgateway.domain.accounting.CostSource.PROVIDER_REPORTED,
            )
            journal.record(context, AttemptSuccess(usage, over))
            money(jdbc, "SELECT available_usd FROM llm_gateway_budget_grant") shouldBe "-10"
            money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "110"
            jdbc.queryForObject("SELECT state FROM llm_gateway_budget_grant", String::class.java) shouldBe "FROZEN"
            shouldThrow<com.example.llmgateway.domain.error.GatewayException> { journal.prepare(context(), price) }
            count(jdbc, "llm_gateway_budget_reservation") shouldBe 1L
        }

        test("journal commits usage children and one immutable outbox receipt").config(enabled = enabled) {
            val jdbc = database()
            val journal = adapter(jdbc)
            val context = context()
            journal.prepare(context, price)
            state(jdbc) shouldBe "PREPARED"
            journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
            journal.record(context, outcome)
            // Reordered components describe the same receipt, even through an independent writer.
            adapter(jdbc).record(context, outcome.copy(usage = Usage(usage.components.reversed())))
            state(jdbc) shouldBe "RECORDED"
            count(jdbc, "llm_gateway_attempt_usage") shouldBe 1L
            count(jdbc, "llm_gateway_usage_component") shouldBe usage.components.size.toLong()
            count(jdbc, "llm_gateway_cost_line") shouldBe outcome.cost.lines.size.toLong()
            count(jdbc, "llm_gateway_accounting_outbox") shouldBe 1L
            jdbc.queryForObject("SELECT total_cost_usd FROM llm_gateway_attempt_usage", BigDecimal::class.java)
                ?.compareTo(outcome.cost.usd) shouldBe 0
            jdbc.queryForObject("SELECT remote_status FROM llm_gateway_attempt_journal", String::class.java) shouldBe "COMPLETED"
            jdbc.queryForObject("SELECT payload->>'execution_id' FROM llm_gateway_accounting_outbox", String::class.java) shouldBe
                context.executionId.value
            shouldThrow<IllegalStateException> { journal.record(context, outcome.copy(providerRequestId = "conflict")) }
            count(jdbc, "llm_gateway_accounting_outbox") shouldBe 1L
        }

        test("outbox failure rolls back terminal usage and components but preserves dispatch intent")
            .config(enabled = enabled) {
                val jdbc = database()
                val journal = adapter(jdbc)
                val context = context()
                journal.prepare(context, price)
                journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                jdbc.execute("ALTER TABLE llm_gateway_accounting_outbox ADD CONSTRAINT fixture_reject_event CHECK (false) NOT VALID")
                shouldThrow<DataIntegrityViolationException> { journal.record(context, outcome) }
                state(jdbc) shouldBe "DISPATCH_INTENT"
                money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "30"
                money(jdbc, "SELECT settled_usd FROM llm_gateway_budget_grant") shouldBe "0"
                jdbc.queryForObject("SELECT receipt_sha256 FROM llm_gateway_attempt_journal", String::class.java) shouldBe null
                listOf("llm_gateway_attempt_usage", "llm_gateway_usage_component", "llm_gateway_cost_line",
                    "llm_gateway_accounting_outbox").forEach { count(jdbc, it) shouldBe 0L }
                jdbc.execute("ALTER TABLE llm_gateway_accounting_outbox DROP CONSTRAINT fixture_reject_event")
                // Retry the same stored completion input, never another model request.
                journal.record(context, outcome)
                state(jdbc) shouldBe "RECORDED"
                count(jdbc, "llm_gateway_accounting_outbox") shouldBe 1L
            }

        test("duplicate or foreign dispatch is rejected and prepared abandon cannot erase intent")
            .config(enabled = enabled) {
                val jdbc = database()
                val journal = adapter(jdbc)
                val context = context()
                val permit = CircuitPermit(context.attemptId, "generation-1")
                journal.prepare(context, price)
                shouldThrow<DataIntegrityViolationException> { journal.prepare(context, price) }
                shouldThrow<IllegalStateException> { journal.record(context, outcome) }
                shouldThrow<IllegalArgumentException> { journal.dispatch(context, permit.copy(owner = AttemptId("foreign"))) }
                shouldThrow<IllegalStateException> { journal.dispatch(context.copy(caller = "foreign"), permit) }
                shouldThrow<IllegalStateException> { journal.dispatch(context.copy(sequence = 2), permit) }
                state(jdbc) shouldBe "PREPARED"
                journal.dispatch(context, permit)
                shouldThrow<IllegalStateException> { journal.dispatch(context, permit) }
                shouldThrow<IllegalStateException> { journal.abandon(context) }
                shouldThrow<EmptyResultDataAccessException> { journal.record(context.copy(tenant = "foreign"), outcome) }
                state(jdbc) shouldBe "DISPATCH_INTENT"
                count(jdbc, "llm_gateway_attempt_usage") shouldBe 0L
            }

        test("database rejects incomplete permit evidence and false completed dispatch")
            .config(enabled = enabled) {
                val jdbc = database()
                val journal = adapter(jdbc)
                val context = context()
                journal.prepare(context, price)
                shouldThrow<DataIntegrityViolationException> {
                    jdbc.update("""UPDATE llm_gateway_attempt_journal SET state = 'DISPATCH_INTENT',
                        remote_status = 'UNKNOWN', permit_generation = 'generation-1',
                        dispatch_intent_at = CURRENT_TIMESTAMP""")
                }
                shouldThrow<DataIntegrityViolationException> {
                    jdbc.update("""UPDATE llm_gateway_attempt_journal SET state = 'DISPATCH_INTENT',
                        remote_status = 'COMPLETED', permit_owner = attempt_id,
                        permit_generation = 'generation-1', dispatch_intent_at = CURRENT_TIMESTAMP""")
                }
                state(jdbc) shouldBe "PREPARED"
                journal.abandon(context)
                state(jdbc) shouldBe "ABANDONED"
                money(jdbc, "SELECT held_usd FROM llm_gateway_budget_grant") shouldBe "0"
                jdbc.queryForObject("SELECT state FROM llm_gateway_budget_reservation", String::class.java) shouldBe "RELEASED"
                shouldThrow<IllegalStateException> { journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1")) }
                count(jdbc, "llm_gateway_accounting_outbox") shouldBe 0L
            }

        test("concurrent independent terminal writers converge to one journal receipt")
            .config(enabled = enabled) {
                val jdbc = database()
                val context = context()
                adapter(jdbc).apply {
                    prepare(context, price)
                    dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                }
                val barrier = CyclicBarrier(2)
                Executors.newFixedThreadPool(2).use { executor ->
                    val futures = (1..2).map {
                        executor.submit {
                            val writer = adapter(jdbc)
                            barrier.await(10, TimeUnit.SECONDS)
                            repeat(5) { writer.record(context, outcome) }
                        }
                    }
                    futures.forEach { it.get(20, TimeUnit.SECONDS) }
                }
                state(jdbc) shouldBe "RECORDED"
                count(jdbc, "llm_gateway_attempt_usage") shouldBe 1L
                count(jdbc, "llm_gateway_accounting_outbox") shouldBe 1L
            }

        test("dispatch and terminal are durable independently of a caller transaction rollback")
            .config(enabled = enabled) {
                val jdbc = database()
                val manager = DataSourceTransactionManager(jdbc.dataSource!!)
                val journal = PostgresAttemptJournalAdapter(jdbc, manager, poolWait(jdbc))
                val context = context()
                TransactionTemplate(manager).executeWithoutResult { outer ->
                    journal.prepare(context, price)
                    journal.dispatch(context, CircuitPermit(context.attemptId, "generation-1"))
                    journal.record(context, outcome)
                    outer.setRollbackOnly()
                }
                state(jdbc) shouldBe "RECORDED"
                count(jdbc, "llm_gateway_attempt_usage") shouldBe 1L
                count(jdbc, "llm_gateway_accounting_outbox") shouldBe 1L
            }
    }

    private fun database(): JdbcTemplate {
        val source = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/journal_fixture?connectTimeout=1&socketTimeout=2&cancelSignalTimeout=1"
            username = "fixture"
            password = "fixture"
        }
        val schema = "journal_" + UUID.randomUUID().toString().replace("-", "")
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate()
        source.schema = schema
        val pool = com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
            dataSource = source
            maximumPoolSize = 12
            minimumIdle = 0
            connectionTimeout = 1000
            validationTimeout = 250
        }).also { pools += it }
        return JdbcTemplate(pool).also { jdbc ->
            jdbc.update("INSERT INTO llm_gateway_budget_binding VALUES ('project-fixture', 'service-fixture', 'project-owned', 'service-owned')")
            jdbc.update("""INSERT INTO llm_gateway_budget_grant
                (grant_id, project_id, owner_epoch, period_start, period_end, face_usd, state)
                VALUES ('grant-fixture', 'project-owned', 1, '2026-09-01T00:00:00Z', '2026-10-01T00:00:00Z', 100, 'ACTIVE')""")
            jdbc.update("INSERT INTO llm_gateway_service_budget (grant_id, service_id, limit_usd) VALUES ('grant-fixture', 'service-owned', 100)")
            jdbc.update("""INSERT INTO llm_gateway_chat_budget_profile
                (deployment_id, provider_model, vendor, revision, max_billable_input_tokens, max_billable_output_tokens, enabled)
                VALUES ('journal-deployment', 'fixture-model', 'OPENAI', 'fixture-v1', 10000, 10000, TRUE)""")
        }
    }

    private fun money(jdbc: JdbcTemplate, sql: String): String = requireNotNull(jdbc.queryForObject(sql, BigDecimal::class.java))
        .stripTrailingZeros().toPlainString()

    private fun executionWriter(jdbc: JdbcTemplate) =
        PostgresExecutionJournalAdapter(jdbc, DataSourceTransactionManager(jdbc.dataSource!!), poolWait(jdbc))
    private fun requestContext(attempt: AttemptContext) = RequestContext(
        requestId = attempt.requestId, executionId = attempt.executionId, tenant = attempt.tenant, caller = attempt.caller,
        startedAt = attempt.budgetAt, deadline = attempt.requestDeadline,
    )
    private fun request(attempt: AttemptContext) = CanonicalChatRequest(
        modelGroup = attempt.deployment.modelGroup, stream = attempt.streaming,
        messages = listOf(CanonicalMessage(MessageRole.USER, "not persisted")),
    )

    private fun poolWait(jdbc: JdbcTemplate): java.time.Duration =
        (jdbc.dataSource as com.zaxxer.hikari.HikariDataSource).let { java.time.Duration.ofMillis(it.connectionTimeout + it.validationTimeout) }
    private fun adapter(jdbc: JdbcTemplate) = PostgresAttemptJournalAdapter(jdbc, DataSourceTransactionManager(jdbc.dataSource!!), poolWait(jdbc))
    private fun state(jdbc: JdbcTemplate) = jdbc.queryForObject("SELECT state FROM llm_gateway_attempt_journal", String::class.java)
    private fun count(jdbc: JdbcTemplate, table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)

    private fun context(): AttemptContext = AttemptContext(
        requestId = RequestId("shared-correlation"), attemptId = AttemptId(UUID.randomUUID().toString()),
        executionId = ExecutionId(UUID.randomUUID().toString()), sequence = 1, kind = AttemptKind.INITIAL,
        deployment = Deployment(DeploymentId("journal-deployment"), Vendor.OPENAI, Dialect.OPENAI,
            ModelGroup("logical-model"), "fixture-model"),
        tenant = "project-fixture", caller = "service-fixture",
        startedAt = Instant.now(), deadline = Instant.now().plusSeconds(60),
        budgetAt = Instant.parse("2026-09-01T00:00:00Z"),
    )
}
