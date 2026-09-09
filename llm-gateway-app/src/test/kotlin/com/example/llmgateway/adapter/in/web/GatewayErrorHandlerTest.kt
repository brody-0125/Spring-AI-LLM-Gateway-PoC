package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus

class GatewayErrorHandlerTest : FunSpec({
    val handler = GatewayErrorHandler()
    val requestId = RequestId("req-error-test")

    test("budget exhaustion is 429 without inventing an automatic reset") {
        val response = handler.handle(GatewayException(GatewayError(
            type = "budget_exceeded", code = "BUDGET_EXCEEDED", category = ErrorCategory.TRANSIENT,
            message = "The available budget does not permit this request", retryable = false, requestId = requestId,
        )))
        response.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
        response.body?.error?.code shouldBe "BUDGET_EXCEEDED"
        response.body?.error?.retryable shouldBe false
        response.headers.getFirst("Retry-After") shouldBe null
    }

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

    test("maps response policy rejection to 422") {
        val response = handler.handle(
            GatewayException(
                GatewayError(
                    type = "response_guardrail_rejected",
                    code = "OUTPUT_POLICY_BLOCKED",
                    category = ErrorCategory.CALLER_FIXABLE,
                    retryable = false,
                    message = "The model response was rejected by a gateway policy",
                    requestId = requestId,
                ),
            ),
        )

        response.statusCode.value() shouldBe HttpStatus.UNPROCESSABLE_ENTITY.value()
        response.body?.error?.code shouldBe "OUTPUT_POLICY_BLOCKED"
    }
})
