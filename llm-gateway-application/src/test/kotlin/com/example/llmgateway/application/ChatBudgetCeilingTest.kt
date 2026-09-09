package com.example.llmgateway.application

import com.example.llmgateway.domain.accounting.ChatBudgetCeiling
import com.example.llmgateway.domain.accounting.ComponentPrice
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.accounting.UsageKey
import com.example.llmgateway.domain.accounting.UsageType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class ChatBudgetCeilingTest : FunSpec({
    test("reservation uses maximum input price without cache prediction or double counted reasoning") {
        val price = PricingSnapshot("v1", BigDecimal("0.001"), BigDecimal("0.002"),
            BigDecimal("0.0001"), BigDecimal("0.003"))
        ChatBudgetCeiling(100, 50).reserveUsd(price).compareTo(BigDecimal("0.4")) shouldBe 0
    }
    test("all known free units are free but unknown cache or output price blocks reservation") {
        val free = PricingSnapshot("free", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        ChatBudgetCeiling(100, 50).reserveUsd(free).signum() shouldBe 0
        shouldThrow<IllegalArgumentException> {
            ChatBudgetCeiling(100, 50).reserveUsd(PricingSnapshot("missing", BigDecimal.ZERO, BigDecimal.ZERO))
        }
        shouldThrow<IllegalArgumentException> {
            ChatBudgetCeiling(100, 50).reserveUsd(PricingSnapshot("missing", BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO))
        }
    }
    test("reservation preserves the smallest supported USD unit") {
        val tiny = BigDecimal("0.000000000000000001")
        val price = PricingSnapshot("precise", tiny, tiny, tiny, tiny)
        ChatBudgetCeiling(1, 1).reserveUsd(price) shouldBe BigDecimal("0.000000000000000002")
    }
    test("numeric overflow is rejected rather than rounded into an approvable amount") {
        val price = PricingSnapshot("large", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)
        shouldThrow<IllegalArgumentException> { ChatBudgetCeiling(Long.MAX_VALUE, 1).reserveUsd(price) }
    }
    test("unknown billing variants cannot inherit a canonical cache price") {
        val price = PricingSnapshot("variant", listOf(
            ComponentPrice(UsageKey(UsageType.CACHE_WRITE_INPUT_TOKENS, "extended"), BigDecimal.ONE),
        ))
        shouldThrow<IllegalArgumentException> { ChatBudgetCeiling(100, 50).reserveUsd(price) }
    }
    test("zero or negative profile bounds are never interpreted as unlimited") {
        shouldThrow<IllegalArgumentException> { ChatBudgetCeiling(0, 1) }
        shouldThrow<IllegalArgumentException> { ChatBudgetCeiling(1, -1) }
    }
})
