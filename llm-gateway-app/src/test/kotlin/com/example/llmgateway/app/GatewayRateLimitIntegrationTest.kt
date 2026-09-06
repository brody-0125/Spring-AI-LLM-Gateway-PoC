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
        "gateway.security.enabled=false",
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.requests-per-minute=60",
        "gateway.rate-limit.burst=1",
        "gateway.guardrails.enabled=false",
        "gateway.resilience.circuit-breaker.enabled=false",
        "gateway.providers.openai.api-key=",
        "gateway.providers.openrouter.api-key=",
        "gateway.providers.bedrock.enabled=false",
    ],
)
@ActiveProfiles("test")
@Import(TestDistributedStateConfiguration::class)
class GatewayRateLimitIntegrationTest : FunSpec() {

    @LocalServerPort
    var port: Int = 0

    init {
        extensions(SpringExtension())

        test("gateway rate limit returns 429 and Retry-After after the configured burst") {
            val body = """{"model":"default","messages":[{"role":"user","content":"hello"}]}"""
            val first = HttpTestClient.post(port, body, requestId = "req-rate-1")
            val second = HttpTestClient.post(port, body, requestId = "req-rate-2")

            first.statusCode() shouldBe HttpStatus.SERVICE_UNAVAILABLE.value()
            second.statusCode() shouldBe HttpStatus.TOO_MANY_REQUESTS.value()
            second.headers().firstValue("Retry-After").orElse("") shouldBe "1"
            second.body() shouldContain "\"type\":\"gateway_rate_limited\""
        }
    }
}
