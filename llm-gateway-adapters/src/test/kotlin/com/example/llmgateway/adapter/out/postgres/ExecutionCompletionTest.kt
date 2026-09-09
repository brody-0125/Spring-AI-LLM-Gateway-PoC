package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.adapter.out.springai.SpringAiVendorConfiguration
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.execution.RequestOutcomeStatus
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.CanonicalMessage
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager

class ExecutionCompletionTest : FunSpec({
    val request = CanonicalChatRequest(ModelGroup("logical"), listOf(CanonicalMessage(MessageRole.USER, "fixture")))
    val success = RequestOutcome(RequestOutcomeStatus.SUCCESS)
    test("production request writer is the execution journal not the legacy aggregation query") {
        HikariDataSource().use { pool ->
            pool.connectionTimeout = 1000
            pool.validationTimeout = 250
            SpringAiVendorConfiguration().requestAccountingPort(
                JdbcTemplate(pool), DataSourceTransactionManager(pool), SimpleMeterRegistry(), Duration.ofSeconds(10),
            ).shouldBeInstanceOf<PostgresExecutionJournalAdapter>()
        }
    }
    for (remaining in listOf(-1L, 2L)) {
        test("execution completion with $remaining seconds left does not acquire a database connection") {
            val meters = SimpleMeterRegistry()
            val writer = PostgresExecutionJournalAdapter(JdbcTemplate(), DataSourceTransactionManager(),
                Duration.ofMillis(1250), meterRegistry = meters)
            val now = Instant.now()
            val context = RequestContext(RequestId("deadline"), startedAt = now.minusSeconds(10), deadline = now.plusSeconds(remaining))
            shouldThrow<TimeoutException> { writer.record(context, request, success) }
            meters.get("llm.gateway.accounting.request.write.failure").counter().count() shouldBe 1.0
        }
    }
    test("impossible completion window is rejected before startup can enable the writer") {
        shouldThrow<IllegalArgumentException> {
            PostgresExecutionJournalAdapter(JdbcTemplate(), DataSourceTransactionManager(), Duration.ofSeconds(2))
        }
    }
    test("a success receipt cannot contain an error") {
        val writer = PostgresExecutionJournalAdapter(JdbcTemplate(), DataSourceTransactionManager(), Duration.ofSeconds(1))
        shouldThrow<IllegalArgumentException> {
            writer.record(RequestContext(RequestId("invalid")), request, success.copy(errorCode = "contradictory"))
        }
    }
    test("the completion reserve provides two individually viable bounded phases") {
        completionPhaseWindow(Duration.ofSeconds(10), Duration.ofMillis(1250)) shouldBe Duration.ofSeconds(5)
        shouldThrow<IllegalArgumentException> { completionPhaseWindow(Duration.ofSeconds(5), Duration.ofMillis(1250)) }
    }
    test("attempt recording leaves the final execution phase untouched when SQL no longer fits") {
        val now = Instant.now()
        val attempt = com.example.llmgateway.domain.execution.AttemptContext(
            requestId = RequestId("phase"), attemptId = com.example.llmgateway.core.primitive.AttemptId("phase-attempt"),
            executionId = com.example.llmgateway.core.primitive.ExecutionId.newId(),
            kind = com.example.llmgateway.domain.execution.AttemptKind.INITIAL,
            sequence = 1, tenant = "fixture", caller = "fixture", streaming = false,
            deployment = com.example.llmgateway.domain.routing.Deployment(
                com.example.llmgateway.core.primitive.DeploymentId("fixture"), com.example.llmgateway.core.primitive.Vendor.OPENAI,
                com.example.llmgateway.core.primitive.Dialect.OPENAI, ModelGroup("logical"), "fixture",
            ),
            startedAt = now, deadline = now.plusSeconds(3), requestDeadline = now.plusSeconds(8),
        )
        val journal = PostgresAttemptJournalAdapter(JdbcTemplate(), DataSourceTransactionManager(), Duration.ofMillis(1250))
        shouldThrow<TimeoutException> { journal.record(attempt, com.example.llmgateway.domain.execution.AttemptCancelled) }
    }
})
