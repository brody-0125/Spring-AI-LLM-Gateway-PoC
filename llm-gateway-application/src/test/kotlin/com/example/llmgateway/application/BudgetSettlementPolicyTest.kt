package com.example.llmgateway.application

import com.example.llmgateway.domain.accounting.BudgetSettlementPolicy
import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.accounting.CostCalculator
import com.example.llmgateway.domain.accounting.CostSource
import com.example.llmgateway.domain.accounting.CostStatus
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.accounting.UsageSource
import com.example.llmgateway.domain.accounting.UsageType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class BudgetSettlementPolicyTest : FunSpec({
    val prices = PricingSnapshot("v1", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)
    val measured = Usage(inputTokens = 10, outputTokens = 2)
    test("measured units at frozen rate card prices can settle") {
        BudgetSettlementPolicy.amount(measured, CostCalculator().calculate(measured, prices))?.compareTo(BigDecimal("12")) shouldBe 0
    }
    test("estimated measurement retains the hold even when rate card cost has ESTIMATED status") {
        val estimate = Usage(measured.components.map { it.copy(source = UsageSource.ESTIMATED) })
        val cost = CostCalculator().calculate(estimate, prices)
        cost.status shouldBe CostStatus.ESTIMATED
        BudgetSettlementPolicy.amount(estimate, cost) shouldBe null
    }
    test("missing cache measurements cannot silently become free cache") {
        val incomplete = Usage(measured.components.filter { it.key.type != UsageType.CACHE_WRITE_INPUT_TOKENS })
        BudgetSettlementPolicy.amount(incomplete, CostCalculator().calculate(incomplete, prices)) shouldBe null
    }
    test("provider reported final cost does not require invented token measurements") {
        val reported = Cost(usd = BigDecimal("1.25"), status = CostStatus.REPORTED, source = CostSource.PROVIDER_REPORTED)
        BudgetSettlementPolicy.amount(Usage(available = false), reported) shouldBe BigDecimal("1.25")
    }
    test("unknown and partial monetary values cannot settle or release a hold") {
        BudgetSettlementPolicy.amount(measured, Cost()) shouldBe null
        BudgetSettlementPolicy.amount(measured, Cost(usd = BigDecimal.ONE, status = CostStatus.PARTIAL, source = CostSource.RATE_CARD)) shouldBe null
    }
})
