package com.example.llmgateway.adapter.out.pricing

import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.accounting.CostCalculator
import com.example.llmgateway.domain.accounting.CostStatus
import com.example.llmgateway.domain.accounting.CostWarning
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.routing.Deployment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class ConfiguredPricingCatalogTest : FunSpec({
    val deployment = Deployment(DeploymentId("test"), Vendor.OPENAI, Dialect.OPENAI, ModelGroup("test"), "test")
    val catalog = ConfiguredPricingCatalogAdapter()

    test("missing and explicitly free prices remain distinct through configuration and calculation") {
        val usage = Usage(inputTokens = 100, outputTokens = 30, cacheReadInputTokens = 20, cacheWriteInputTokens = 10)
        listOf<BigDecimal?>(null, BigDecimal.ZERO, BigDecimal("0.002")).forEach { price ->
            val snapshot = catalog.resolve(deployment.copy(
                inputCostPer1kUsd = price, outputCostPer1kUsd = price,
                cacheReadInputCostPer1kUsd = price, cacheWriteInputCostPer1kUsd = price,
            ), Instant.EPOCH)
            val cost = CostCalculator().calculate(usage, snapshot)
            if (price == null) {
                snapshot.inputCostPerTokenUsd shouldBe null
                cost.status shouldBe CostStatus.UNKNOWN
                cost.amount shouldBe null
                cost.warnings shouldBe setOf(CostWarning.MISSING_INPUT_PRICE, CostWarning.MISSING_OUTPUT_PRICE,
                    CostWarning.MISSING_CACHE_READ_PRICE, CostWarning.MISSING_CACHE_WRITE_PRICE)
            } else {
                snapshot.inputCostPerTokenUsd shouldBe price.movePointLeft(3).setScale(18)
                cost.warnings.isEmpty() shouldBe true
                cost.usd.compareTo(price.movePointLeft(3).multiply(BigDecimal("130"))) shouldBe 0
            }
        }
    }

    test("negative, subprecision and oversized configured prices cannot silently become free") {
        shouldThrow<IllegalArgumentException> { deployment.copy(inputCostPer1kUsd = BigDecimal("-0.1")) }
        shouldThrow<ArithmeticException> { BigDecimal("0.0000000000000001").toTokenPriceUsd() }
        shouldThrow<IllegalArgumentException> { BigDecimal("1000000000").toTokenPriceUsd() }
        BigDecimal("0.000000000000001").toTokenPriceUsd() shouldBe BigDecimal("0.000000000000000001")
    }
})
