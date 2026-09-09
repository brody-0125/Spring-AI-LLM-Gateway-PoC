package com.example.llmgateway.app

import com.example.llmgateway.adapter.`in`.web.toUsageDtoOrNull
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.accounting.UsageComponent
import com.example.llmgateway.domain.accounting.UsageKey
import com.example.llmgateway.domain.accounting.UsageType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UsageContractMappingTest : FunSpec({
    test("incomplete or non-token measurements never become fabricated Chat token totals") {
        Usage(listOf(UsageComponent(UsageKey(UsageType.RERANK_QUERIES), 3))).toUsageDtoOrNull() shouldBe null
        Usage(listOf(UsageComponent(UsageKey(UsageType.INPUT_TOKENS), 10),
            UsageComponent(UsageKey(UsageType.OUTPUT_TOKENS), null))).toUsageDtoOrNull() shouldBe null
        Usage(available = false).toUsageDtoOrNull() shouldBe null
        Usage(inputTokens = 0, outputTokens = 0).toUsageDtoOrNull()?.totalTokens shouldBe 0L
        Usage(inputTokens = 100, outputTokens = 40, cacheReadInputTokens = 20,
            reasoningOutputTokens = 30).toUsageDtoOrNull()?.totalTokens shouldBe 140L
    }
})
