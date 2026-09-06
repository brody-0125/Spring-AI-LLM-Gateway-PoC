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
        "gateway.security.enabled=true",
        "gateway.security.clients=test-secret=test-bff:test-tenant,admin-secret=ops:platform:admin",
        "gateway.rate-limit.enabled=false",
        "gateway.guardrails.enabled=false",
        "gateway.providers.openai.api-key=",
        "gateway.providers.openrouter.api-key=",
        "gateway.providers.bedrock.enabled=false",
    ],
)
@ActiveProfiles("test")
@Import(TestDistributedStateConfiguration::class)
class GatewaySecurityIntegrationTest : FunSpec() {

    @LocalServerPort
    var port: Int = 0

    init {
        extensions(SpringExtension())

        test("missing gateway bearer token returns 401") {
            val response = HttpTestClient.post(
                port = port,
                body = """{"model":"default","messages":[{"role":"user","content":"hello"}]}""",
                requestId = "req-auth-missing",
            )

            response.statusCode() shouldBe HttpStatus.UNAUTHORIZED.value()
            response.body() shouldContain "\"type\":\"authentication_required\""
        }

        test("valid gateway bearer token populates client context and reaches routing") {
            val response = HttpTestClient.post(
                port = port,
                body = """{"model":"default","messages":[{"role":"user","content":"hello"}]}""",
                requestId = "req-auth-valid",
                authorization = "Bearer test-secret",
            )

            response.statusCode() shouldBe HttpStatus.SERVICE_UNAVAILABLE.value()
            response.body() shouldContain "\"type\":\"no_available_deployment\""
        }

        test("administrator can read the runtime routing snapshot") {
            val response = HttpTestClient.getWithAuthorization(
                port = port,
                path = "/internal/v1/routing",
                authorization = "Bearer admin-secret",
            )

            response.statusCode() shouldBe HttpStatus.OK.value()
            response.body() shouldContain "\"version\":1"
        }

        test("ordinary clients cannot access the routing control plane") {
            val response = HttpTestClient.getWithAuthorization(
                port = port,
                path = "/internal/v1/routing",
                authorization = "Bearer test-secret",
            )

            response.statusCode() shouldBe HttpStatus.FORBIDDEN.value()
            response.body() shouldContain "\"type\":\"administrator_required\""
        }
    }
}
