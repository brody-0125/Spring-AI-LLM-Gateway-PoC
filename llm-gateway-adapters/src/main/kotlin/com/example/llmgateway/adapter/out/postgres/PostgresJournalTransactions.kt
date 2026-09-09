package com.example.llmgateway.adapter.out.postgres

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Uses native JDBC/PG timeouts, not application background writes. A remote commit can still be ambiguous. */
internal class PostgresJournalTransactions(
    private val jdbc: JdbcTemplate,
    private val manager: PlatformTransactionManager,
    private val connectionWait: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    // Covers query-timeout second granularity and normal commit/rollback transport cleanup.
    private val cleanupReserve = Duration.ofSeconds(2)

    init { require(!connectionWait.isNegative && !connectionWait.isZero) }

    fun write(deadline: Instant, action: () -> Unit) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Journal write interrupted")
        val seconds = Duration.between(clock.instant(), deadline).minus(connectionWait).minus(cleanupReserve).seconds
        if (seconds < 1) throw TimeoutException("Insufficient time to begin a journal transaction")
        val transaction = TransactionTemplate(manager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            timeout = seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        transaction.executeWithoutResult {
            val connection = DataSourceUtils.getConnection(requireNotNull(jdbc.dataSource))
            check(connection.networkTimeout in 1..2000) { "Journal connections require a bounded network read timeout of at most two seconds" }
            // LOCAL settings cannot leak through a returned pooled connection.
            jdbc.queryForMap(
                "SELECT set_config('statement_timeout', ?, true) AS statement_timeout, set_config('lock_timeout', ?, true) AS lock_timeout",
                "${minOf(seconds, 2) * 1000}ms", "500ms",
            )
            action()
            if (!clock.instant().plus(cleanupReserve).isBefore(deadline)) {
                throw TimeoutException("Journal transaction exhausted its completion margin")
            }
        }
    }
}
