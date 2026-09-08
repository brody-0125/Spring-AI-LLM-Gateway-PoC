package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.ProviderException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.ConnectException
import java.net.SocketTimeoutException
import com.example.llmgateway.domain.model.ProviderFailurePhase
import com.example.llmgateway.domain.model.RequestDisposition

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

    test("marks connection failures as not sent") {
        val mapped = ProviderFailureMapper.map(
            IllegalStateException("wrapper", ConnectException("connection refused")),
            Vendor.OPENAI,
        )

        mapped.phase shouldBe ProviderFailurePhase.CONNECT
        mapped.requestDisposition shouldBe RequestDisposition.NOT_SENT
    }

    test("marks read timeouts as sent but incomplete") {
        val mapped = ProviderFailureMapper.map(
            IllegalStateException("wrapper", SocketTimeoutException("read timed out")),
            Vendor.OPENAI,
        )

        mapped.phase shouldBe ProviderFailurePhase.READ
        mapped.requestDisposition shouldBe RequestDisposition.SENT_UNKNOWN
    }
})
