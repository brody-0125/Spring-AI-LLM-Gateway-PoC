package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.RequestContext
import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Shares the caller's journal transaction; never commits or performs provider I/O. */
internal class PostgresExecutionState(private val jdbc: JdbcTemplate) {
    fun prepare(context: AttemptContext) {
        val request = RequestContext(
            requestId = context.requestId, executionId = context.executionId,
            tenant = context.tenant, caller = context.caller,
            startedAt = context.budgetAt, deadline = context.requestDeadline,
        )
        check(lock(request, context.deployment.modelGroup, context.streaming)["state"] == "OPEN") {
            "A recorded execution cannot start another attempt"
        }
    }

    fun bindReservation(context: AttemptContext) {
        check(jdbc.update(
            """UPDATE llm_gateway_execution_journal e
               SET project_id = g.project_id, service_id = r.service_id
               FROM llm_gateway_budget_reservation r JOIN llm_gateway_budget_grant g ON g.grant_id = r.grant_id
               WHERE e.execution_id = ? AND r.attempt_id = ? AND e.state = 'OPEN'
                 AND (e.project_id IS NULL OR (e.project_id = g.project_id AND e.service_id = r.service_id))""",
            context.executionId.value, context.attemptId.value,
        ) == 1) { "An execution cannot change its budget identity" }
    }

    fun lock(context: RequestContext, model: ModelGroup, streaming: Boolean): Map<String, Any?> {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        jdbc.update(
            """INSERT INTO llm_gateway_execution_journal
               (execution_id, correlation_id, tenant, caller, model_group, operation, streaming,
                started_at, deadline, trace_id, state)
               VALUES (?, ?, ?, ?, ?, 'CHAT_COMPLETION', ?, ?, ?, ?, 'OPEN')
               ON CONFLICT (execution_id) DO NOTHING""",
            context.executionId.value, context.requestId.value, context.tenant, context.caller,
            model.value, streaming, Timestamp.from(context.startedAt), Timestamp.from(context.deadline), context.traceId,
        )
        return jdbc.queryForMap(
            """SELECT state, outcome, error_type, error_code FROM llm_gateway_execution_journal
               WHERE execution_id = ? AND correlation_id = ? AND tenant = ? AND caller = ?
                 AND model_group = ? AND streaming = ? AND operation = 'CHAT_COMPLETION'
                 AND started_at = ? AND deadline = ? FOR UPDATE""",
            context.executionId.value, context.requestId.value, context.tenant, context.caller,
            model.value, streaming, Timestamp.from(context.startedAt), Timestamp.from(context.deadline),
        )
    }
}
