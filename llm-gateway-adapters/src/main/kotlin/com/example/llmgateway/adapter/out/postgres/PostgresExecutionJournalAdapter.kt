package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.RequestAccountingPort
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.execution.RequestOutcomeStatus
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import java.time.Duration
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/** Records execution completion, not a synchronous usage projection or a second financial settlement. */
class PostgresExecutionJournalAdapter(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    connectionWait: Duration,
    completionWindow: Duration = Duration.ofSeconds(10),
    private val meterRegistry: MeterRegistry? = null,
) : RequestAccountingPort {
    private val phaseWindow = completionPhaseWindow(completionWindow, connectionWait)
    private val transaction = PostgresJournalTransactions(jdbc, transactionManager, connectionWait)
    private val completion = CompletionWriteRetry(window = phaseWindow)
    private val execution = PostgresExecutionState(jdbc)

    override fun record(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) {
        require(outcome.status != RequestOutcomeStatus.SUCCESS || (outcome.errorType == null && outcome.errorCode == null))
        try { completion.execute(context.deadline) { deadline ->
            transaction.write(deadline) terminal@{
                val row = execution.lock(context, request.modelGroup, request.stream)
                if (row["state"] == "RECORDED") {
                    check(row["outcome"] == outcome.status.name && row["error_type"] == outcome.errorType &&
                        row["error_code"] == outcome.errorCode) { "Conflicting execution completion requires reconciliation" }
                    return@terminal
                }
                if (outcome.status == RequestOutcomeStatus.SUCCESS) {
                    check(jdbc.queryForObject(
                        """SELECT COALESCE((SELECT state = 'RECORDED' AND remote_status = 'COMPLETED'
                             FROM llm_gateway_attempt_journal WHERE execution_id = ?
                             ORDER BY attempt_sequence DESC LIMIT 1), FALSE)
                           AND NOT EXISTS (SELECT 1 FROM llm_gateway_attempt_journal
                             WHERE execution_id = ? AND state IN ('PREPARED', 'DISPATCH_INTENT'))""",
                        Boolean::class.java, context.executionId.value, context.executionId.value,
                    ) == true) { "Execution success requires durably recorded attempts" }
                }
                check(jdbc.update(
                    """UPDATE llm_gateway_execution_journal SET state = 'RECORDED', outcome = ?,
                       error_type = ?, error_code = ?, trace_id = COALESCE(trace_id, ?), recorded_at = CURRENT_TIMESTAMP
                       WHERE execution_id = ? AND state = 'OPEN'""",
                    outcome.status.name, outcome.errorType, outcome.errorCode, context.traceId, context.executionId.value,
                ) == 1)
                jdbc.update(
                    """INSERT INTO llm_gateway_accounting_outbox (event_id, execution_id, schema_version, payload)
                       VALUES (?, ?, 1, jsonb_build_object('schema_version', 1, 'type', 'execution.recorded',
                           'execution_id', CAST(? AS text)))""",
                    "execution-recorded:${context.executionId.value}", context.executionId.value, context.executionId.value,
                )
            }
        } } catch (error: Exception) {
            runCatching { meterRegistry?.counter("llm.gateway.accounting.request.write.failure")?.increment() }
            throw error
        }
    }
}
