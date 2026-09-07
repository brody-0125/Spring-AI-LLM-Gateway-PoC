package com.example.llmgateway.application

import com.example.llmgateway.application.operator.DefaultCostCalculationOperator
import com.example.llmgateway.application.port.out.PricingCatalogPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.CostStatus
import com.example.llmgateway.domain.model.CostWarning
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.PricingSnapshot
import com.example.llmgateway.domain.model.Usage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.Instant

class CostCalculationOperatorTest : FunSpec({
    test("pricing lookup failure is recorded as unknown without throwing") {
        val operator = DefaultCostCalculationOperator(
            object : PricingCatalogPort {
                override fun resolve(deployment: Deployment, at: Instant): PricingSnapshot =
                    error("pricing database unavailable")
            },
        )

        val cost = operator.calculate(
            deployment = deployment,
            usage = Usage(inputTokens = 10, outputTokens = 5),
            at = Instant.EPOCH,
        )

        cost.status shouldBe CostStatus.UNKNOWN
        cost.warnings shouldContain CostWarning.PRICING_UNAVAILABLE
        cost.usd.signum() shouldBe 0
    }

    test("available usage with a complete price snapshot is reported") {
        val operator = DefaultCostCalculationOperator(
            object : PricingCatalogPort {
                override fun resolve(deployment: Deployment, at: Instant) = PricingSnapshot(
                    version = "price-v1",
                    inputCostPerTokenUsd = "0.000001".toBigDecimal(),
                    outputCostPerTokenUsd = "0.000002".toBigDecimal(),
                )
            },
        )

        val cost = operator.calculate(deployment, Usage(inputTokens = 10, outputTokens = 5), Instant.EPOCH)

        cost.status shouldBe CostStatus.REPORTED
        cost.pricingVersion shouldBe "price-v1"
        cost.usd shouldBe "0.000020000000".toBigDecimal()
    }
}) {
    companion object {
        private val deployment = Deployment(
            id = DeploymentId("openai-test"),
            vendor = Vendor.OPENAI,
            dialect = Dialect.OPENAI,
            modelGroup = ModelGroup("default"),
            model = "gpt-test",
        )
    }
}
