package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.model.ErrorCategory
import com.example.llmgateway.domain.model.GatewayError
import com.example.llmgateway.domain.model.GatewayException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus

class GatewayErrorHandlerTest : FunSpec({
    val handler = GatewayErrorHandler()
    val requestId = RequestId("req-error-test")

    test("maps gateway timeout to 504 without leaking internal details") {
        val response = handler.handle(
            GatewayException(
                GatewayError(
                    type = "gateway_timeout",
                    code = "GATEWAY_TIMEOUT",
                    category = ErrorCategory.TRANSIENT,
                    retryable = true,
                    message = "The gateway request deadline was exceeded",
                    requestId = requestId,
                ),
            ),
        )

        response.statusCode shouldBe HttpStatus.GATEWAY_TIMEOUT
        response.headers.getFirst("X-Request-Id") shouldBe requestId.value
        response.body?.error?.code shouldBe "GATEWAY_TIMEOUT"
        response.body?.error?.message shouldBe "The gateway request deadline was exceeded"
    }

    test("maps policy rejection to 422 and keeps retry metadata optional") {
        val response = handler.handle(
            GatewayException(
                GatewayError(
                    type = "guardrail_rejected",
                    code = "POLICY_BLOCKED",
                    category = ErrorCategory.CALLER_FIXABLE,
                    retryable = false,
                    message = "The request was rejected by a gateway guardrail",
                    requestId = requestId,
                ),
            ),
        )

        response.statusCode.value() shouldBe HttpStatus.UNPROCESSABLE_ENTITY.value()
        response.body?.error?.code shouldBe "POLICY_BLOCKED"
        response.body?.error?.retryAfterSeconds shouldBe null
    }
})
