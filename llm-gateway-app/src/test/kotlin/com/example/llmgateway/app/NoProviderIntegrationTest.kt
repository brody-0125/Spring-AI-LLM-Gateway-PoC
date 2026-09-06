package com.example.llmgateway.app

import com.example.llmgateway.bootstrap.GatewayApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "gateway.providers.openai.api-key=",
        "gateway.providers.openrouter.api-key=",
        "gateway.providers.bedrock.enabled=false",
    ],
)
@ActiveProfiles("test")
@Import(TestDistributedStateConfiguration::class)
class NoProviderIntegrationTest : FunSpec() {

    @LocalServerPort
    var port: Int = 0

    init {
        extensions(SpringExtension())

        test("request with no configured provider returns retryable 503 contract") {
            val response = HttpTestClient.post(
                port = port,
                body = """{"model":"default","messages":[{"role":"user","content":"hello"}]}""",
                requestId = "req-no-provider",
            )

            response.statusCode() shouldBe HttpStatus.SERVICE_UNAVAILABLE.value()
            response.headers().firstValue("X-Request-Id").orElse(null) shouldBe "req-no-provider"
            response.body() shouldContain "\"type\":\"no_available_deployment\""
            response.body() shouldContain "\"retryable\":true"
        }
    }
}
