package com.example.llmgateway.application

import com.example.llmgateway.application.operator.DefaultCostCalculationOperator
import com.example.llmgateway.domain.accounting.CostStatus
import com.example.llmgateway.domain.accounting.CostWarning
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.accounting.Usage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class CostCalculationOperatorTest : FunSpec({
    test("missing captured pricing is unknown, not free") {
        val cost = DefaultCostCalculationOperator().calculate(null, Usage(inputTokens = 10, outputTokens = 5))
        cost.status shouldBe CostStatus.UNKNOWN
        cost.warnings shouldContain CostWarning.PRICING_UNAVAILABLE
        cost.usd.signum() shouldBe 0
        cost.amount shouldBe null
    }

    test("calculation uses only the supplied immutable pricing") {
        val pricing = PricingSnapshot("price-v1", "0.000001".toBigDecimal(), "0.000002".toBigDecimal())
        val cost = DefaultCostCalculationOperator().calculate(pricing, Usage(inputTokens = 10, outputTokens = 5))
        cost.status shouldBe CostStatus.ESTIMATED
        cost.source shouldBe com.example.llmgateway.domain.accounting.CostSource.RATE_CARD
        cost.amount shouldBe cost.usd
        cost.pricingVersion shouldBe "price-v1"
        cost.usd shouldBe "0.000020000000000000".toBigDecimal()
    }

    test("free rate-card estimates and unavailable usage do not share an amount contract") {
        val price = PricingSnapshot("free", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)
        val known = DefaultCostCalculationOperator().calculate(price, Usage(inputTokens = 10))
        known.amount shouldBe java.math.BigDecimal.ZERO.setScale(18)
        known.status shouldBe CostStatus.ESTIMATED
        known.source shouldBe com.example.llmgateway.domain.accounting.CostSource.RATE_CARD
        val unknown = DefaultCostCalculationOperator().calculate(price, Usage(available = false))
        unknown.status shouldBe CostStatus.UNKNOWN
        unknown.amount shouldBe null
    }

    test("the smallest valid rate remains nonzero for one token and cannot be rounded below its quantum") {
        val tiny = "0.000000000000000001".toBigDecimal()
        val price = PricingSnapshot("tiny", tiny, java.math.BigDecimal.ZERO)
        val cost = DefaultCostCalculationOperator().calculate(price, Usage(inputTokens = 1))
        cost.amount shouldBe tiny
        cost.usd.signum() shouldBe 1
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            PricingSnapshot("too-small", "0.0000000000000000001".toBigDecimal(), null)
        }
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            PricingSnapshot("too-large", "1000000".toBigDecimal(), null)
        }
    }
})
