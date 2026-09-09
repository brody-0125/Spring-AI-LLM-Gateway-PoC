package com.example.llmgateway.application

import com.example.llmgateway.domain.accounting.Usage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UsageArithmeticTest : FunSpec({
    test("token arithmetic rejects overflow while preserving valid boundary values") {
        shouldThrow<IllegalArgumentException> { Usage(inputTokens = Long.MAX_VALUE, outputTokens = 1) }
        shouldThrow<IllegalArgumentException> {
            Usage(cacheReadInputTokens = Long.MAX_VALUE, cacheWriteInputTokens = 1)
        }
        val boundary = Usage(inputTokens = Long.MAX_VALUE,
            cacheReadInputTokens = Long.MAX_VALUE - 1, cacheWriteInputTokens = 1)
        boundary.totalTokens shouldBe Long.MAX_VALUE
        boundary.regularInputTokens shouldBe 0L
    }

    test("cumulative merging cannot combine individually valid values into an overflowing usage") {
        val input = Usage(inputTokens = Long.MAX_VALUE)
        shouldThrow<IllegalArgumentException> { input.mergeCumulative(Usage(outputTokens = 1)) }
        val read = Usage(cacheReadInputTokens = Long.MAX_VALUE)
        shouldThrow<IllegalArgumentException> { read.mergeCumulative(Usage(cacheWriteInputTokens = 1)) }
    }
})
