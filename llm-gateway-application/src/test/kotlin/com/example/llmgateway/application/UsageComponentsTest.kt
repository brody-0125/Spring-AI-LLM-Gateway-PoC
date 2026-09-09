package com.example.llmgateway.application

import com.example.llmgateway.domain.accounting.ComponentPrice
import com.example.llmgateway.domain.accounting.CostCalculator
import com.example.llmgateway.domain.accounting.CostLine
import com.example.llmgateway.domain.accounting.CostSource
import com.example.llmgateway.domain.accounting.CostStatus
import com.example.llmgateway.domain.accounting.CostWarning
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.accounting.UsageComponent
import com.example.llmgateway.domain.accounting.UsageKey
import com.example.llmgateway.domain.accounting.UsageSource
import com.example.llmgateway.domain.accounting.UsageType
import com.example.llmgateway.domain.accounting.UsageUnit
import com.example.llmgateway.domain.inference.chat.ProviderResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class UsageComponentsTest : FunSpec({
    val calculator = CostCalculator()
    fun key(type: UsageType, variant: String = "") = UsageKey(type, variant)
    fun price(type: UsageType, value: String?, variant: String = "") =
        ComponentPrice(key(type, variant), value?.toBigDecimal())

    test("query document-block and invocation quantities retain their own unit and are not tokens") {
        listOf(
            UsageType.RERANK_QUERIES to UsageUnit.QUERY,
            UsageType.RERANK_DOCUMENT_BLOCKS to UsageUnit.DOCUMENT_BLOCK,
            UsageType.TOOL_INVOCATIONS to UsageUnit.INVOCATION,
        ).forEach { (type, unit) ->
            val usage = Usage(listOf(UsageComponent(key(type), 3)))
            val cost = calculator.calculate(usage, PricingSnapshot("units", listOf(price(type, "0.005"))))
            cost.amount shouldBe BigDecimal("0.015000000000000000")
            cost.lines.single().unit shouldBe unit
            cost.lines.single().quantity shouldBe 3L
            cost.source shouldBe CostSource.RATE_CARD
            cost.status shouldBe CostStatus.ESTIMATED
            usage.totalTokens shouldBe 0L
            usage.hasCompleteTokenTotals shouldBe false
        }
    }

    test("cache variants partition input and reasoning is a non-additive output detail") {
        val usage = Usage(listOf(
            UsageComponent(key(UsageType.INPUT_TOKENS), 100),
            UsageComponent(key(UsageType.OUTPUT_TOKENS), 40),
            UsageComponent(key(UsageType.CACHE_READ_INPUT_TOKENS), 20),
            UsageComponent(key(UsageType.CACHE_WRITE_INPUT_TOKENS, "short"), 10),
            UsageComponent(key(UsageType.CACHE_WRITE_INPUT_TOKENS, "long"), 5),
            UsageComponent(key(UsageType.REASONING_OUTPUT_TOKENS), 30),
        ))
        val cost = calculator.calculate(usage, PricingSnapshot("variants", listOf(
            price(UsageType.INPUT_TOKENS, "1"), price(UsageType.OUTPUT_TOKENS, "2"),
            price(UsageType.CACHE_READ_INPUT_TOKENS, "0.1"),
            price(UsageType.CACHE_WRITE_INPUT_TOKENS, "0.5", "short"),
            price(UsageType.CACHE_WRITE_INPUT_TOKENS, "1.5", "long"),
        )))
        usage.totalTokens shouldBe 140L
        usage.regularInputTokens shouldBe 65L
        cost.amount shouldBe BigDecimal("159.500000000000000000")
        cost.lines.size shouldBe 5
        cost.lines.none { it.key.type == UsageType.REASONING_OUTPUT_TOKENS } shouldBe true
        shouldThrow<IllegalArgumentException> { price(UsageType.REASONING_OUTPUT_TOKENS, "2") }
    }

    test("unknown quantity and unknown price stay separate from explicit zero and free rates") {
        val queries = key(UsageType.RERANK_QUERIES)
        val tools = key(UsageType.TOOL_INVOCATIONS, "search")
        val snapshot = PricingSnapshot("partial", listOf(ComponentPrice(queries, BigDecimal("0.1")),
            ComponentPrice(tools, BigDecimal.ZERO)))
        val partial = calculator.calculate(Usage(listOf(UsageComponent(queries, 2), UsageComponent(tools, null))), snapshot)
        partial.status shouldBe CostStatus.PARTIAL
        partial.amount shouldBe BigDecimal("0.200000000000000000")
        partial.lines.single { it.key == tools }.amount shouldBe null
        partial.warnings shouldBe setOf(CostWarning.PARTIAL_USAGE)
        val free = calculator.calculate(Usage(listOf(UsageComponent(tools, 3))), snapshot)
        free.status shouldBe CostStatus.ESTIMATED
        free.amount shouldBe BigDecimal.ZERO.setScale(18)
        val missing = calculator.calculate(Usage(listOf(UsageComponent(tools, 3))), PricingSnapshot("missing", emptyList()))
        missing.status shouldBe CostStatus.UNKNOWN
        missing.amount shouldBe null
        missing.lines.single().amount shouldBe null
        missing.warnings shouldBe setOf(CostWarning.MISSING_COMPONENT_PRICE)
        val absent = calculator.calculate(Usage(listOf(UsageComponent(tools, null))), snapshot)
        absent.amount shouldBe null
        absent.status shouldBe CostStatus.UNKNOWN
    }

    test("unknown cache cannot silently price every input token as regular") {
        val usage = Usage(listOf(UsageComponent(key(UsageType.INPUT_TOKENS), 100),
            UsageComponent(key(UsageType.CACHE_READ_INPUT_TOKENS), null)))
        val cost = calculator.calculate(usage, PricingSnapshot("cache", "1".toBigDecimal(), null))
        cost.status shouldBe CostStatus.UNKNOWN
        cost.lines.single { it.key.type == UsageType.INPUT_TOKENS }.quantity shouldBe null
        cost.lines.all { it.amount == null } shouldBe true
    }

    test("cumulative merge is by key and reported quantities replace estimates without addition") {
        val queries = key(UsageType.RERANK_QUERIES)
        val first = Usage(listOf(UsageComponent(queries, 10, UsageSource.ESTIMATED)))
        val reported = first.mergeCumulative(Usage(listOf(UsageComponent(queries, 7))))
        reported.quantity(UsageType.RERANK_QUERIES) shouldBe 7L
        reported.components.single().source shouldBe UsageSource.PROVIDER_REPORTED
        reported.mergeCumulative(Usage(listOf(UsageComponent(queries, null)))).quantity(UsageType.RERANK_QUERIES) shouldBe 7L
        reported.mergeCumulative(Usage(listOf(UsageComponent(queries, 20, UsageSource.ESTIMATED))))
            .quantity(UsageType.RERANK_QUERIES) shouldBe 7L
        reported.mergeCumulative(Usage(listOf(UsageComponent(queries, 9)))).quantity(UsageType.RERANK_QUERIES) shouldBe 9L
    }

    test("component collections are frozen and conflicting keys or provenance are rejected") {
        val queries = key(UsageType.RERANK_QUERIES)
        val input = mutableListOf(UsageComponent(queries, 1))
        val prices = mutableListOf(ComponentPrice(queries, BigDecimal.ONE))
        val usage = Usage(input)
        val snapshot = PricingSnapshot("immutable", prices)
        input.clear()
        prices.clear()
        usage.components.size shouldBe 1
        snapshot.prices.size shouldBe 1
        shouldThrow<UnsupportedOperationException> { (usage.components as MutableList).clear() }
        shouldThrow<UnsupportedOperationException> { (snapshot.prices as MutableList).clear() }
        val cost = calculator.calculate(usage, snapshot)
        shouldThrow<UnsupportedOperationException> { (cost.lines as MutableList).clear() }
        shouldThrow<IllegalArgumentException> { Usage(listOf(UsageComponent(queries, 1), UsageComponent(queries, 2))) }
        shouldThrow<IllegalArgumentException> { PricingSnapshot("duplicate", listOf(
            ComponentPrice(queries, BigDecimal.ONE), ComponentPrice(queries, BigDecimal.ZERO))) }
        shouldThrow<IllegalArgumentException> { UsageComponent(queries, -1) }
        shouldThrow<IllegalArgumentException> { UsageComponent(queries, null, UsageSource.PROVIDER_REPORTED) }
        shouldThrow<IllegalArgumentException> { UsageComponent(queries, 0, UsageSource.UNKNOWN) }
        shouldThrow<IllegalArgumentException> { key(UsageType.TOOL_INVOCATIONS, "bad variant") }
    }

    test("omitted provider usage is unknown and a generic stream accumulator does not invent token components") {
        val absent = ProviderResponse("reply").usage
        absent.available shouldBe false
        calculator.calculate(absent, PricingSnapshot("price", BigDecimal.ONE, BigDecimal.ONE))
            .status shouldBe CostStatus.UNKNOWN
        val key = key(UsageType.TOOL_INVOCATIONS, "search")
        val merged = Usage(emptyList()).mergeCumulative(Usage(listOf(UsageComponent(key, 2))))
        merged.components.size shouldBe 1
        merged.totalTokens shouldBe 0L
    }

    test("variant totals reject overflow before accounting") {
        shouldThrow<IllegalArgumentException> {
            Usage(listOf(
                UsageComponent(key(UsageType.RERANK_DOCUMENT_BLOCKS, "small"), Long.MAX_VALUE),
                UsageComponent(key(UsageType.RERANK_DOCUMENT_BLOCKS, "large"), 1),
            ))
        }
    }

    test("zero-quantity detail cannot make an entirely unpriced positive usage look known") {
        val usage = Usage(inputTokens = 10)
        val missing = calculator.calculate(usage, PricingSnapshot("missing", null, null))
        missing.status shouldBe CostStatus.UNKNOWN
        missing.amount shouldBe null
        val noneUsed = calculator.calculate(Usage(), PricingSnapshot("no-work", null, null))
        noneUsed.status shouldBe CostStatus.ESTIMATED
        noneUsed.amount shouldBe BigDecimal.ZERO.setScale(18)
        shouldThrow<IllegalArgumentException> {
            CostLine(key(UsageType.REASONING_OUTPUT_TOKENS), 1, BigDecimal.ONE, BigDecimal.ONE,
                CostStatus.ESTIMATED, CostSource.RATE_CARD)
        }
    }
})
