package com.example.llmgateway.adapter.out.postgres

import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeoutException

internal class CompletionWriteRetry(
    private val clock: Clock = Clock.systemUTC(),
    private val maxAttempts: Int = 3,
    private val window: Duration = Duration.ofSeconds(5),
    private val sleep: (Duration) -> Unit = { Thread.sleep(it) },
    private val jitter: (Long) -> Long = { ThreadLocalRandom.current().nextLong(it + 1) },
) {
    init {
        require(maxAttempts in 1..10)
        require(!window.isNegative && !window.isZero)
    }

    fun execute(requestDeadline: Instant, write: (Instant) -> Unit) {
        val deadline = minOf(requestDeadline, clock.instant().plus(window))
        for (attempt in 1..maxAttempts) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Completion write interrupted")
            if (!clock.instant().isBefore(deadline)) throw TimeoutException("Completion write deadline exceeded")
            try {
                write(deadline)
                return
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                if (attempt == maxAttempts || Thread.currentThread().isInterrupted || !retryable(error)) throw error
                val delay = Duration.ofMillis(jitter(minOf(100L, 25L shl (attempt - 1))))
                if (!clock.instant().plus(delay).isBefore(deadline)) throw error
                try { sleep(delay) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
    }

    private fun retryable(error: Throwable): Boolean {
        var cause: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (cause != null && seen.add(cause)) {
            if (cause is SQLException) {
                val state = cause.sqlState.orEmpty()
                // An explicit SQL failure must not be reclassified by its subtype or a nested connection error.
                if (state.isNotBlank()) return state.startsWith("08") || state in setOf("40001", "40P01", "55P03", "53300", "57P01", "57P02", "57P03")
                if (cause is SQLTransientConnectionException) return true
            }
            cause = cause.cause
        }
        return false
    }
}
