package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.domain.model.GatewayResponse
import com.example.llmgateway.domain.model.Usage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class GatewayResponseMapperTest : FunSpec({
    test("omits usage when the provider did not return usage metadata") {
        val response = GatewayResponse(
            id = "chatcmpl-test",
            model = "default",
            text = "hello",
            usage = Usage(available = false),
            finishReason = "length",
        )

        val mapped = GatewayResponseMapper.toContract(response)

        mapped.usage.shouldBeNull()
        mapped.choices.single().finishReason shouldBe "length"
    }
})
