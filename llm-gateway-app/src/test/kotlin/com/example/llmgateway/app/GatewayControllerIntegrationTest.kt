package com.example.llmgateway.app

import com.example.llmgateway.application.port.`in`.ChatCompletionCommandIn
import com.example.llmgateway.application.port.`in`.ChatCompletionQueryIn
import com.example.llmgateway.bootstrap.GatewayApplication
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayEvent
import com.example.llmgateway.domain.model.GatewayResponse
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.Usage
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Import(
    GatewayControllerIntegrationTest.TestGatewayConfiguration::class,
    TestDistributedStateConfiguration::class,
)
class GatewayControllerIntegrationTest : FunSpec() {

    init {
        extensions(SpringExtension())

    test("chat completion exposes canonical response and request id") {
        val response = post(
            body = """
                {
                  "model": "default",
                  "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent(),
            requestId = "req-http-test",
        )

        response.statusCode() shouldBe HttpStatus.OK.value()
        response.headers().firstValue("X-Request-Id").orElse(null) shouldBe "req-http-test"
        response.body() shouldContain "\"role\":\"assistant\""
        response.body() shouldContain "\"content\":\"test response\""
        response.body() shouldContain "\"total_tokens\":0"
    }

    test("invalid role is caller fixable") {
        val response = post(
            body = """
                {
                  "model": "default",
                  "messages": [{"role": "tool", "content": "not enabled"}]
                }
            """.trimIndent(),
        )

        response.statusCode() shouldBe HttpStatus.BAD_REQUEST.value()
        response.body() shouldContain "\"code\":\"INVALID_REQUEST\""
        response.body() shouldContain "\"retryable\":false"
        response.body() shouldNotContain "\"category\""
    }

    test("streaming response uses SSE and emits done marker") {
        val response = post(
            body = """
                {
                  "model": "default",
                  "stream": true,
                  "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent(),
            accept = MediaType.TEXT_EVENT_STREAM_VALUE,
        )

        response.statusCode() shouldBe HttpStatus.OK.value()
        response.headers().firstValue("Content-Type").orElse("") shouldStartWith "text/event-stream"
        response.body() shouldContain "test"
        response.body() shouldContain "\"model\":\"default\""
        response.body() shouldContain "\"finish_reason\":\"length\""
        response.body() shouldContain "[DONE]"
    }

    test("malformed body returns gateway error contract") {
        val response = post("{ malformed", requestId = "req-invalid-body")

        response.statusCode() shouldBe HttpStatus.BAD_REQUEST.value()
        response.headers().firstValue("X-Request-Id").orElse(null) shouldBe "req-invalid-body"
        response.body() shouldContain "\"type\":\"invalid_request\""
        response.body() shouldContain "\"code\":\"INVALID_REQUEST\""
        response.body() shouldContain "\"retryable\":false"
        response.body() shouldNotContain "\"retry_after\""
    }

    test("provider-specific request fields are not accepted by the public contract") {
        val response = post(
            body = """
                {
                  "model": "default",
                  "messages": [{"role": "user", "content": "hello"}],
                  "provider": "openai"
                }
            """.trimIndent(),
        )

        response.statusCode() shouldBe HttpStatus.BAD_REQUEST.value()
        response.body() shouldContain "\"type\":\"invalid_request\""
    }

    test("null message content is rejected by the public contract") {
        val response = post(
            body = """
                {
                  "model": "default",
                  "messages": [{"role": "user", "content": null}]
                }
            """.trimIndent(),
        )

        response.statusCode() shouldBe HttpStatus.BAD_REQUEST.value()
        response.body() shouldContain "\"type\":\"invalid_request\""
    }

    test("max token aliases cannot be combined") {
        val response = post(
            body = """
                {
                  "model": "default",
                  "max_tokens": 10,
                  "max_completion_tokens": 10,
                  "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent(),
        )

        response.statusCode() shouldBe HttpStatus.BAD_REQUEST.value()
        response.body() shouldContain "\"type\":\"invalid_request\""
    }

    test("gateway creates a request id when the caller does not provide one") {
        val response = post(
            body = """
                {
                  "model": "default",
                  "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent(),
        )

        response.statusCode() shouldBe HttpStatus.OK.value()
        response.headers().firstValue("X-Request-Id").orElse("") shouldStartWith "req_"
    }

    test("invalid request id is rejected without echoing the invalid value") {
        val response = post(
            body = """
                {
                  "model": "default",
                  "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent(),
            requestId = "invalid request id",
        )

        response.statusCode() shouldBe HttpStatus.BAD_REQUEST.value()
        val responseRequestId = response.headers().firstValue("X-Request-Id").orElse("")
        responseRequestId shouldStartWith "req_"
        response.body() shouldContain "\"request_id\":\"$responseRequestId\""
        response.body() shouldContain "\"type\":\"invalid_request\""
    }

    test("unexpected server failure uses a non-retryable 500 contract") {
        val response = post(
            body = """
                {
                  "model": "unexpected",
                  "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent(),
            requestId = "req-unexpected",
        )

        response.statusCode() shouldBe HttpStatus.INTERNAL_SERVER_ERROR.value()
        response.body() shouldContain "\"type\":\"gateway_error\""
        response.body() shouldContain "\"code\":\"GATEWAY_INTERNAL_ERROR\""
        response.body() shouldContain "\"retryable\":false"
    }

    test("OpenAPI contract is served from the application") {
        val response = HttpTestClient.get(port, "/v3/api-docs.yaml")

        response.statusCode() shouldBe HttpStatus.OK.value()
        response.body() shouldContain "openapi: 3.0.3"
        response.body() shouldContain "/v1/chat/completions:"
        response.body() shouldContain "X-Request-Id"
        response.body() shouldContain "traceparent"
        response.body() shouldContain "event named error"
        response.body() shouldContain "ChatDelta"
        response.body() shouldNotContain "/internal/v1/routing"
        response.body() shouldNotContain "stream_options"
        response.body() shouldNotContain "frequency_penalty"
        response.body() shouldNotContain "presence_penalty"
        response.body() shouldNotContain "category"
    }
    }

    @LocalServerPort
    var port: Int = 0

    fun post(
        body: String,
        requestId: String? = null,
        accept: String? = null,
    ) = HttpTestClient.post(port, body, requestId, accept)

    @TestConfiguration(proxyBeanMethods = false)
    class TestGatewayConfiguration {
        @Bean
        @Primary
        fun testChatCompletionQueryIn(): ChatCompletionQueryIn = object : ChatCompletionQueryIn {
            override fun complete(
                request: CanonicalChatRequest,
                context: RequestContext,
            ): GatewayResponse {
                if (request.modelGroup.value == "unexpected") {
                    throw IllegalStateException("test failure")
                }
                return GatewayResponse(
                    id = "chatcmpl-test",
                    model = "default",
                    text = "test response",
                    usage = Usage(),
                )
            }

        }

        @Bean
        @Primary
        fun testChatCompletionCommandIn(): ChatCompletionCommandIn = object : ChatCompletionCommandIn {
            override fun stream(
                request: CanonicalChatRequest,
                context: RequestContext,
            ): Sequence<GatewayEvent> = sequenceOf(
                GatewayEvent.Delta("chatcmpl-test", "test", model = "default"),
                GatewayEvent.Complete(
                    id = "chatcmpl-test",
                    usage = Usage(inputTokens = 1, outputTokens = 1),
                    model = "default",
                    finishReason = "length",
                ),
            )
        }
    }

}
