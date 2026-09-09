package com.example.llmgateway.app

import com.example.llmgateway.application.port.`in`.CompleteChatCommandIn
import com.example.llmgateway.application.operator.RequestLifecycleOperator
import com.example.llmgateway.application.port.out.RequestAccountingPort
import com.example.llmgateway.application.port.`in`.StreamChatCommandIn
import com.example.llmgateway.bootstrap.GatewayApplication
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayCompleteEvent
import com.example.llmgateway.domain.inference.chat.GatewayDeltaEvent
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.inference.chat.GatewayResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.util.concurrent.ConcurrentLinkedQueue
import org.springframework.beans.factory.annotation.Autowired
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

    test("concurrent JSON and SSE reuse correlation without sharing execution identity") {
        val correlation = "parallel-correlation"
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            (1..10).map { index ->
                executor.submit {
                    val response = post(
                        body = """{"model":"default","stream":${index % 2 == 0},"messages":[{"role":"user","content":"hello"}]}""",
                        requestId = correlation,
                    )
                    response.statusCode() shouldBe 200
                    response.headers().firstValue("X-Request-Id").orElse(null) shouldBe correlation
                    response.headers().firstValue("X-Execution-Id").isPresent shouldBe false
                    response.body() shouldNotContain "execution_id"
                    response.body() shouldNotContain "attempt_kind"
                }
            }.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        }
        val contexts = capturedRequests.filter { it.requestId.value == correlation }
        contexts.size shouldBe 10
        contexts.map { it.executionId }.toSet().size shouldBe 10
    }

    test("JSON accounting failure is non-retryable 503 without storage details or Retry-After") {
        val response = post(
            """{"model":"default","messages":[{"role":"user","content":"accounting-failure"}]}""",
            requestId = "accounting-json",
        )
        response.statusCode() shouldBe 503
        response.body() shouldContain "\"code\":\"OUTCOME_UNKNOWN\""
        response.body() shouldContain "\"retryable\":false"
        response.body() shouldNotContain "private database detail"
        response.body() shouldNotContain "test response"
        response.headers().firstValue("Retry-After").isPresent shouldBe false
        response.headers().firstValue("X-Request-Id").orElse("") shouldBe "accounting-json"
    }

    test("SSE request accounting failure withholds the success terminal and DONE after visible content") {
        val response = post(
            """{"model":"default","stream":true,"messages":[{"role":"user","content":"accounting-failure"}]}""",
            requestId = "accounting-stream",
            accept = MediaType.TEXT_EVENT_STREAM_VALUE,
        )
        response.statusCode() shouldBe 200
        response.body() shouldContain "\"content\":\"test\""
        response.body() shouldContain "event:error"
        response.body() shouldContain "\"code\":\"OUTCOME_UNKNOWN\""
        response.body() shouldContain "\"retryable\":false"
        response.body() shouldNotContain "[DONE]"
        response.body() shouldNotContain "\"finish_reason\":\"length\""
        response.body() shouldNotContain "private database detail"
        response.headers().firstValue("Retry-After").isPresent shouldBe false
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

    test("disconnecting an incremental HTTP consumer closes its gateway stream") {
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:$port/v1/chat/completions"))
            .timeout(java.time.Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                """{"model":"default","stream":true,"messages":[{"role":"user","content":"cancel-probe"}]}"""))
            .build()
        try {
            java.net.http.HttpClient.newHttpClient().use { client ->
                val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
                response.statusCode() shouldBe 200
                response.body().bufferedReader().use { reader ->
                    reader.readLine() shouldContain "data:"
                }
                streamCancellationProbe.closed.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
                streamCancellationProbe.closes.get() shouldBe 1
                (streamCancellationProbe.frames.get() < 1000) shouldBe true
            }
        } finally {
            streamCancellationProbe.stream.get()?.close()
        }
        streamCancellationProbe.closes.get() shouldBe 1
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

    @Autowired
    lateinit var capturedRequests: ConcurrentLinkedQueue<RequestContext>

    @Autowired
    lateinit var streamCancellationProbe: StreamCancellationProbe

    fun post(
        body: String,
        requestId: String? = null,
        accept: String? = null,
    ) = HttpTestClient.post(port, body, requestId, accept)

    @TestConfiguration(proxyBeanMethods = false)
    class TestGatewayConfiguration {
        @Bean
        fun capturedRequests(): ConcurrentLinkedQueue<RequestContext> = ConcurrentLinkedQueue()

        @Bean
        fun streamCancellationProbe() = StreamCancellationProbe()

        @Bean
        @Primary
        fun testCompleteChatCommandIn(capturedRequests: ConcurrentLinkedQueue<RequestContext>): CompleteChatCommandIn = object : CompleteChatCommandIn {
            override fun complete(
                request: CanonicalChatRequest,
                context: RequestContext,
            ): GatewayResponse {
                capturedRequests.add(context)
                if (request.modelGroup.value == "unexpected") {
                    throw IllegalStateException("test failure")
                }
                val response = GatewayResponse(
                    id = "chatcmpl-test",
                    model = "default",
                    text = "test response",
                    usage = Usage(),
                )
                return if (request.messages.firstOrNull()?.content == "accounting-failure") {
                    failingAccountingLifecycle().execute(request, context) { response }
                } else response
            }

        }

        @Bean
        @Primary
        fun testStreamChatCommandIn(
            capturedRequests: ConcurrentLinkedQueue<RequestContext>,
            streamCancellationProbe: StreamCancellationProbe,
        ): StreamChatCommandIn = object : StreamChatCommandIn {
            override fun stream(
                request: CanonicalChatRequest,
                context: RequestContext,
            ): com.example.llmgateway.domain.stream.CloseableStream<GatewayEvent> {
                capturedRequests.add(context)
                if (request.messages.firstOrNull()?.content == "cancel-probe") {
                    return com.example.llmgateway.domain.stream.ManagedStream<GatewayEvent> { scope -> sequence {
                        scope.own(AutoCloseable {
                            streamCancellationProbe.closes.incrementAndGet()
                            streamCancellationProbe.closed.countDown()
                        })
                        repeat(1000) {
                            streamCancellationProbe.frames.incrementAndGet()
                            yield(GatewayDeltaEvent("cancel", "x".repeat(4096), model = "default"))
                            Thread.sleep(20)
                        }
                    } }.also { streamCancellationProbe.stream.set(it) }
                }
                val stream = com.example.llmgateway.domain.stream.ManagedStream { sequenceOf(
                GatewayDeltaEvent("chatcmpl-test", "test", model = "default"),
                GatewayCompleteEvent(
                    id = "chatcmpl-test",
                    usage = Usage(inputTokens = 1, outputTokens = 1),
                    model = "default",
                    finishReason = "length",
                ),
            ) }
                return if (request.messages.firstOrNull()?.content == "accounting-failure") {
                    failingAccountingLifecycle().stream(request, context) { stream }
                } else stream
            }
        }
    }

}

private fun failingAccountingLifecycle() = RequestLifecycleOperator(
    accounting = object : RequestAccountingPort {
        override fun record(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) {
            error("private database detail")
        }
    },
)
