package com.example.llmgateway.adapter.out.postgres

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeoutException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager

class CompletionWriteRetryTest : FunSpec({
    val now = Instant.parse("2026-09-01T00:00:00Z")
    val fixed = Clock.fixed(now, ZoneOffset.UTC)
    for (state in listOf("08006", "40001", "40P01", "55P03", "53300", "57P01")) {
        test("completion SQL state $state retries only the same write with one shared deadline") {
            var calls = 0
            val deadlines = mutableListOf<Instant>()
            val waits = mutableListOf<Duration>()
            CompletionWriteRetry(clock = fixed, sleep = { waits += it }, jitter = { it }).execute(now.plusSeconds(60)) {
                deadlines += it
                calls++
                if (calls < 3) throw IllegalStateException("wrapped JDBC failure", SQLException("fixture", state))
            }
            calls shouldBe 3
            deadlines.distinct() shouldBe listOf(now.plusSeconds(5))
            waits shouldBe listOf(Duration.ofMillis(25), Duration.ofMillis(50))
        }
    }
    for (error in listOf(SQLException("constraint", "23514"), SQLException("cancelled", "57014"), IllegalStateException("receipt conflict"))) {
        test("permanent completion failure ${error.message} is not retried") {
            var calls = 0
            val thrown = shouldThrow<Exception> {
                CompletionWriteRetry(clock = fixed, sleep = { error("unexpected wait") }).execute(now.plusSeconds(60)) {
                    calls++
                    throw error
                }
            }
            thrown shouldBe error
            calls shouldBe 1
        }
    }
    test("persistent pool failure stops after the bounded number of writes") {
        var calls = 0
        shouldThrow<SQLTransientConnectionException> {
            CompletionWriteRetry(clock = fixed, sleep = {}, jitter = { 0 }).execute(now.plusSeconds(60)) {
                calls++
                throw SQLTransientConnectionException("pool exhausted")
            }
        }
        calls shouldBe 3
    }
    for (state in listOf("23514", "57014", "28000")) {
        for (error in listOf(
            SQLException("permanent failure", state, SQLTransientConnectionException("nested connection failure")),
            SQLTransientConnectionException("driver subtype with permanent state", state),
        )) {
            test("explicit SQL state $state overrides ${error.message}") {
                var calls = 0
                val thrown = shouldThrow<SQLException> {
                    CompletionWriteRetry(clock = fixed, sleep = {}, jitter = { 0 }).execute(now.plusSeconds(60)) {
                        calls++
                        throw error
                    }
                }
                thrown shouldBe error
                calls shouldBe 1
            }
        }
    }
    test("a state-less SQL wrapper still permits a transient cause to be classified") {
        var calls = 0
        CompletionWriteRetry(clock = fixed, sleep = {}, jitter = { 0 }).execute(now.plusSeconds(60)) {
            calls++
            if (calls == 1) throw SQLException("wrapper", SQLException("connection", "08006"))
        }
        calls shouldBe 2
    }
    test("an expired deadline does not enter the write") {
        shouldThrow<TimeoutException> {
            CompletionWriteRetry(clock = fixed).execute(now) { error("write must not start") }
        }
    }
    test("request deadline limits the retry window and excessive backoff is not started") {
        var calls = 0
        shouldThrow<SQLException> {
            CompletionWriteRetry(clock = fixed, sleep = { error("unexpected wait") }, jitter = { it })
                .execute(now.plusMillis(10)) { deadline ->
                    deadline shouldBe now.plusMillis(10)
                    calls++
                    throw SQLException("serialization", "40001")
                }
        }
        calls shouldBe 1
    }
    test("interrupted backoff stops without another SQL attempt") {
        var calls = 0
        try {
            shouldThrow<InterruptedException> {
                CompletionWriteRetry(clock = fixed, sleep = { throw InterruptedException("cancel") }).execute(now.plusSeconds(60)) {
                    calls++
                    throw SQLException("serialization", "40001")
                }
            }
            Thread.currentThread().isInterrupted shouldBe true
        } finally { Thread.interrupted() }
        calls shouldBe 1
    }
    test("journal refuses a deadline that cannot cover pool acquisition SQL and cleanup") {
        val transactions = PostgresJournalTransactions(JdbcTemplate(), DataSourceTransactionManager(), Duration.ofSeconds(1), fixed)
        shouldThrow<TimeoutException> { transactions.write(now.plusSeconds(3)) { error("must not connect or write") } }
    }
})
