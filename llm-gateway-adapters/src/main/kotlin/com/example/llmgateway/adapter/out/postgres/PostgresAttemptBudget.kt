package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.domain.accounting.ChatBudgetCeiling
import com.example.llmgateway.domain.accounting.BudgetSettlementPolicy
import com.example.llmgateway.domain.accounting.CostCalculator
import com.example.llmgateway.domain.accounting.CostSource
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import java.math.BigDecimal
import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Owned by the journal transaction; no separate commits or provider I/O. */
internal class PostgresAttemptBudget(private val jdbc: JdbcTemplate) {
    fun reserve(context: AttemptContext, pricing: PricingSnapshot?) {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        val prices = requireNotNull(pricing) { "An immutable price snapshot is required" }
        val profile = jdbc.queryForMap(
            """SELECT * FROM llm_gateway_chat_budget_profile
               WHERE deployment_id = ? AND provider_model = ? AND vendor = ? AND enabled = TRUE""",
            context.deployment.id.value, context.deployment.model, context.deployment.vendor.name,
        )
        val ceiling = ChatBudgetCeiling((profile["max_billable_input_tokens"] as Number).toLong(),
            (profile["max_billable_output_tokens"] as Number).toLong())
        context.requestedOutputTokens?.let {
            if (it <= 0 || it.toLong() > ceiling.outputTokens) throw GatewayException(GatewayError(
                type = "invalid_request", code = "UNSUPPORTED_CAPABILITY", category = ErrorCategory.CALLER_FIXABLE,
                message = "The requested output limit is not supported by this model profile",
                retryable = false, requestId = context.requestId,
            ))
        }
        val amount = ceiling.reserveUsd(prices)
        val grant = jdbc.queryForMap(
            """SELECT g.*, b.service_id FROM llm_gateway_budget_grant g
               JOIN llm_gateway_budget_binding b ON b.project_id = g.project_id
               WHERE b.tenant = ? AND b.caller = ? AND g.period_start <= ? AND g.period_end > ?
               FOR UPDATE OF g""",
            context.tenant, context.caller, Timestamp.from(context.budgetAt), Timestamp.from(context.budgetAt),
        )
        val grantId = grant["grant_id"] as String
        val serviceId = grant["service_id"] as String
        val service = jdbc.queryForMap(
            "SELECT limit_usd - held_usd - settled_usd AS available FROM llm_gateway_service_budget WHERE grant_id = ? AND service_id = ? FOR UPDATE",
            grantId, serviceId,
        )
        if (grant["state"] != "ACTIVE" || (grant["available_usd"] as BigDecimal) < amount ||
            (service["available"] as BigDecimal) < amount) throw GatewayException(GatewayError(
            type = "budget_exceeded", code = "BUDGET_EXCEEDED", category = ErrorCategory.TRANSIENT,
            message = "The available budget does not permit this request", retryable = false,
            requestId = context.requestId,
        ))
        check(jdbc.update("UPDATE llm_gateway_budget_grant SET held_usd = held_usd + ? WHERE grant_id = ?",
            amount, grantId) == 1)
        check(jdbc.update("UPDATE llm_gateway_service_budget SET held_usd = held_usd + ? WHERE grant_id = ? AND service_id = ?",
            amount, grantId, serviceId) == 1)
        check(jdbc.update(
            """INSERT INTO llm_gateway_budget_reservation
               (attempt_id, grant_id, service_id, owner_epoch, period_start, period_end, profile_revision,
                pricing_version, input_bound, output_bound, input_price_usd, output_price_usd,
                cache_read_price_usd, cache_write_price_usd, reserved_usd, state)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HELD')""",
            context.attemptId.value, grantId, serviceId, grant["owner_epoch"], grant["period_start"], grant["period_end"],
            profile["revision"], prices.version, ceiling.inputTokens, ceiling.outputTokens,
            prices.inputCostPerTokenUsd, prices.outputCostPerTokenUsd,
            prices.cacheReadInputCostPerTokenUsd, prices.cacheWriteInputCostPerTokenUsd, amount,
        ) == 1)
    }

    fun release(context: AttemptContext) = finish(context, null, release = true)

    fun settle(context: AttemptContext, outcome: AttemptOutcome) = finish(context, outcome, release = false)

    private fun finish(context: AttemptContext, outcome: AttemptOutcome?, release: Boolean) {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        val reservation = jdbc.queryForMap(
            "SELECT * FROM llm_gateway_budget_reservation WHERE attempt_id = ? FOR UPDATE", context.attemptId.value,
        )
        check(reservation["state"] == "HELD") { "Reservation already has a financial outcome" }
        val success = outcome as? AttemptSuccess
        val cost = success?.cost
        val settlement = success?.let { BudgetSettlementPolicy.amount(it.usage, it.cost) }
        if (!release && settlement == null) {
            check(jdbc.update("UPDATE llm_gateway_budget_reservation SET state = 'REVIEW_REQUIRED' WHERE attempt_id = ?",
                context.attemptId.value) == 1)
            return // Partial/unknown usage and uncertain remote termination keep the full hold.
        }
        val actual = if (release) BigDecimal.ZERO else requireNotNull(settlement)
        if (!release && cost?.source == CostSource.RATE_CARD) {
            val frozen = PricingSnapshot(reservation["pricing_version"] as String,
                reservation["input_price_usd"] as BigDecimal, reservation["output_price_usd"] as BigDecimal,
                reservation["cache_read_price_usd"] as BigDecimal, reservation["cache_write_price_usd"] as BigDecimal)
            val recalculated = CostCalculator().calculate(requireNotNull(success).usage, frozen)
            check(recalculated.usd.compareTo(actual) == 0) { "Settlement differs from frozen reservation prices" }
        }
        require(actual.stripTrailingZeros().scale() <= 18 && actual < BigDecimal("1000000000000")) {
            "Settlement exceeds the supported exact USD range"
        }
        val grantId = reservation["grant_id"] as String
        val serviceId = reservation["service_id"] as String
        val reserved = reservation["reserved_usd"] as BigDecimal
        val grant = jdbc.queryForMap("SELECT owner_epoch FROM llm_gateway_budget_grant WHERE grant_id = ? FOR UPDATE", grantId)
        check(grant["owner_epoch"] == reservation["owner_epoch"]) { "Budget owner changed; reconciliation is required" }
        val service = jdbc.queryForMap("SELECT held_usd FROM llm_gateway_service_budget WHERE grant_id = ? AND service_id = ? FOR UPDATE",
            grantId, serviceId)
        check((service["held_usd"] as BigDecimal) >= reserved)
        val boundExceeded = actual > reserved || (success != null &&
            (success.usage.inputTokens > (reservation["input_bound"] as Number).toLong() ||
                success.usage.outputTokens > (reservation["output_bound"] as Number).toLong()))
        check(jdbc.update(
            """UPDATE llm_gateway_budget_grant SET held_usd = held_usd - ?, settled_usd = settled_usd + ?,
               state = CASE WHEN ? THEN 'FROZEN' ELSE state END WHERE grant_id = ? AND held_usd >= ?""",
            reserved, actual, boundExceeded, grantId, reserved,
        ) == 1)
        check(jdbc.update("UPDATE llm_gateway_service_budget SET held_usd = held_usd - ?, settled_usd = settled_usd + ? WHERE grant_id = ? AND service_id = ?",
            reserved, actual, grantId, serviceId) == 1)
        check(jdbc.update("UPDATE llm_gateway_budget_reservation SET state = ?, actual_usd = ? WHERE attempt_id = ? AND state = 'HELD'",
            if (release) "RELEASED" else "SETTLED", if (release) null else actual, context.attemptId.value) == 1)
    }
}
