package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.ProviderException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProviderFailureMapperTest : FunSpec({
    test("maps unknown provider failures to a retryable sanitized failure") {
        val mapped = ProviderFailureMapper.map(
            IllegalStateException("secret api key and provider payload"),
            Vendor.OPENROUTER,
        )

        mapped.vendor shouldBe Vendor.OPENROUTER
        mapped.statusCode shouldBe 503
        mapped.providerCode shouldBe "provider_error"
        mapped.message shouldBe "Provider request failed"
    }

    test("preserves an already classified provider failure") {
        val original = ProviderException(Vendor.AWS_BEDROCK, 429, "throttling", "provider throttled")

        ProviderFailureMapper.map(IllegalStateException("wrapper", original), Vendor.AWS_BEDROCK) shouldBe original
    }
})
