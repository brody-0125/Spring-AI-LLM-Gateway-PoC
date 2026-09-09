package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.AttemptJournalPort
import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.execution.AttemptCancelled
import com.example.llmgateway.domain.execution.AttemptCancelledWithUsage
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptFailure
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import com.example.llmgateway.domain.routing.CircuitPermit
import io.micrometer.core.instrument.MeterRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class PostgresAttemptJournalAdapter(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    connectionWait: java.time.Duration,
    meterRegistry: MeterRegistry? = null,
    completionWindow: java.time.Duration = java.time.Duration.ofSeconds(10),
) : AttemptJournalPort {
    private val phaseWindow = completionPhaseWindow(completionWindow, connectionWait)
    private val transaction = PostgresJournalTransactions(jdbc, transactionManager, connectionWait)
    private val completion = CompletionWriteRetry(window = phaseWindow)
    // Joins the journal transaction: usage children, terminal receipt and outbox commit together.
    private val accounting = PostgresAttemptAccountingAdapter(
        jdbc, TransactionTemplate(transactionManager), meterRegistry = meterRegistry,
    )
    private val budget = PostgresAttemptBudget(jdbc)
    private val execution = PostgresExecutionState(jdbc)

    override fun prepare(context: AttemptContext, pricing: PricingSnapshot?) {
        transaction.write(context.deadline) {
            execution.prepare(context)
            check(jdbc.update(
                """INSERT INTO llm_gateway_attempt_journal
                   (attempt_id, execution_id, correlation_id, tenant, caller, attempt_sequence, attempt_kind,
                    deployment_id, model_group, streaming, pricing_version, started_at, deadline, request_deadline, state, remote_status)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', 'NOT_SENT')""",
                context.attemptId.value, context.executionId.value, context.requestId.value, context.tenant,
                context.caller, context.sequence, context.kind.name, context.deployment.id.value,
                context.deployment.modelGroup.value, context.streaming, pricing?.version,
                Timestamp.from(context.startedAt), Timestamp.from(context.deadline),
                Timestamp.from(context.requestDeadline),
            ) == 1)
            budget.reserve(context, pricing)
            execution.bindReservation(context)
        }
    }

    override fun dispatch(context: AttemptContext, permit: CircuitPermit) {
        require(permit.owner == context.attemptId && permit.generation.isNotBlank()) { "Invalid permit owner" }
        transaction.write(context.deadline) {
            check(jdbc.update(
                """UPDATE llm_gateway_attempt_journal
                   SET state = 'DISPATCH_INTENT', remote_status = 'UNKNOWN', permit_owner = ?,
                       permit_generation = ?, dispatch_intent_at = CURRENT_TIMESTAMP
                   WHERE attempt_id = ? AND execution_id = ? AND tenant = ? AND caller = ? AND state = 'PREPARED'
                     AND correlation_id = ? AND attempt_sequence = ? AND attempt_kind = ?
                     AND deployment_id = ? AND model_group = ? AND streaming = ?""",
                permit.owner.value, permit.generation, context.attemptId.value, context.executionId.value,
                context.tenant, context.caller,
                context.requestId.value, context.sequence, context.kind.name, context.deployment.id.value,
                context.deployment.modelGroup.value, context.streaming,
            ) == 1) { "Dispatch requires an owned, undispatched attempt" }
        }
    }

    override fun abandon(context: AttemptContext) {
        transaction.write(context.requestDeadline) {
            check(jdbc.update(
                """UPDATE llm_gateway_attempt_journal SET state = 'ABANDONED'
                   WHERE attempt_id = ? AND execution_id = ? AND tenant = ? AND caller = ? AND state = 'PREPARED'""",
                context.attemptId.value, context.executionId.value, context.tenant, context.caller,
            ) == 1) { "Only an owned PREPARED attempt can be abandoned" }
            budget.release(context)
        }
    }

    override fun record(context: AttemptContext, outcome: AttemptOutcome) {
        val receipt = receipt(outcome)
        completion.execute(context.requestDeadline.minus(phaseWindow)) { deadline -> transaction.write(deadline) terminal@{
            val row = jdbc.queryForMap(
                """SELECT state, receipt_sha256, pricing_version FROM llm_gateway_attempt_journal
                   WHERE attempt_id = ? AND execution_id = ? AND tenant = ? AND caller = ?
                     AND correlation_id = ? AND attempt_sequence = ? AND attempt_kind = ?
                     AND deployment_id = ? AND model_group = ? AND streaming = ? FOR UPDATE""",
                context.attemptId.value, context.executionId.value, context.tenant, context.caller,
                context.requestId.value, context.sequence, context.kind.name, context.deployment.id.value,
                context.deployment.modelGroup.value, context.streaming,
            )
            if (row["state"] == "RECORDED") {
                check(row["receipt_sha256"] == receipt) { "Conflicting terminal receipt requires reconciliation" }
                return@terminal
            }
            check(row["state"] == "DISPATCH_INTENT") { "Terminal outcome requires dispatch intent" }
            val cost = cost(outcome)
            check(cost.pricingVersion == null || cost.pricingVersion == row["pricing_version"]) {
                "Terminal pricing version differs from the prepared attempt"
            }
            // A compatibility writer must not have inserted this new execution's receipt separately.
            check(jdbc.queryForObject(
                "SELECT COUNT(*) FROM llm_gateway_attempt_usage WHERE request_id = ? AND attempt_id = ?",
                Long::class.java, context.executionId.value, context.attemptId.value,
            ) == 0L) { "Attempt usage was written outside its journal transaction" }
            accounting.record(context, outcome)
            budget.settle(context, outcome)
            val remoteStatus = if (outcome is AttemptSuccess) "COMPLETED" else "UNKNOWN"
            check(jdbc.update(
                """UPDATE llm_gateway_attempt_journal SET state = 'RECORDED', recorded_at = CURRENT_TIMESTAMP,
                   receipt_sha256 = ?, remote_status = ? WHERE attempt_id = ? AND state = 'DISPATCH_INTENT'""",
                receipt, remoteStatus, context.attemptId.value,
            ) == 1)
            jdbc.update(
                """INSERT INTO llm_gateway_accounting_outbox (event_id, attempt_id, schema_version, payload)
                   VALUES (?, ?, 1, jsonb_build_object('schema_version', 1, 'type', 'attempt.recorded',
                     'execution_id', CAST(? AS text), 'attempt_id', CAST(? AS text),
                     'remote_status', CAST(? AS text)))""",
                "attempt-recorded:${context.attemptId.value}", context.attemptId.value,
                context.executionId.value, context.attemptId.value, remoteStatus,
            )
        } }
    }

    private fun usage(outcome: AttemptOutcome): Usage = when (outcome) {
        is AttemptSuccess -> outcome.usage
        is AttemptFailure -> outcome.usage
        is AttemptCancelledWithUsage -> outcome.usage
        AttemptCancelled -> Usage(available = false)
    }

    private fun cost(outcome: AttemptOutcome): Cost = when (outcome) {
        is AttemptSuccess -> outcome.cost
        is AttemptFailure -> outcome.cost
        is AttemptCancelledWithUsage -> outcome.cost
        AttemptCancelled -> Cost()
    }

    /** Versioned, length-prefixed receipt; no provider payload or JVM class names are persisted. */
    private fun receipt(outcome: AttemptOutcome): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun text(value: String?) { output.writeBoolean(value != null); if (value != null) output.writeUTF(value) }
            fun number(value: java.math.BigDecimal?) = text(value?.stripTrailingZeros()?.toPlainString())
            output.writeInt(1)
            text(when (outcome) {
                is AttemptSuccess -> "SUCCESS"
                is AttemptFailure -> "FAILURE"
                is AttemptCancelledWithUsage -> "CANCELLED_WITH_USAGE"
                AttemptCancelled -> "CANCELLED"
            })
            text((outcome as? AttemptFailure)?.failureClass?.name)
            text(when (outcome) {
                is AttemptSuccess -> outcome.providerRequestId
                is AttemptFailure -> outcome.providerRequestId
                is AttemptCancelledWithUsage -> outcome.providerRequestId
                AttemptCancelled -> null
            })
            val components = usage(outcome).components.sortedWith(compareBy({ it.key.type.name }, { it.key.variant }))
            output.writeInt(components.size)
            components.forEach { component ->
                text(component.key.type.name); text(component.key.variant); text(component.source.name)
                output.writeBoolean(component.quantity != null); component.quantity?.let(output::writeLong)
            }
            val cost = cost(outcome)
            text(cost.status.name); text(cost.source.name); text(cost.pricingVersion)
            listOf(cost.usd, cost.inputUsd, cost.outputUsd, cost.cacheReadUsd, cost.cacheWriteUsd).forEach(::number)
            val warnings = cost.warnings.map { it.name }.sorted()
            output.writeInt(warnings.size); warnings.forEach(::text)
            val lines = cost.lines.sortedWith(compareBy({ it.key.type.name }, { it.key.variant }))
            output.writeInt(lines.size)
            lines.forEach { line ->
                text(line.key.type.name); text(line.key.variant); text(line.status.name); text(line.source.name)
                output.writeBoolean(line.quantity != null); line.quantity?.let(output::writeLong)
                number(line.unitPriceUsd); number(line.amount)
            }
        }
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }
}
