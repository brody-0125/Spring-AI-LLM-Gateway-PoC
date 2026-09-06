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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Import(TestDistributedStateConfiguration::class)
class SpringAiProviderFallbackIntegrationTest : FunSpec() {

    @LocalServerPort
    var port: Int = 0

    init {
        extensions(SpringExtension())
        beforeSpec {
            if (ProviderContainer.enabled) {
                ProviderContainer.resetMappings()
                ProviderContainer.stubAuthorizationResponse(
                    authorization = ProviderContainer.primaryKey,
                    status = 503,
                    content = "temporary outage",
                )
                ProviderContainer.stubAuthorizationResponse(
                    authorization = ProviderContainer.fallbackKey,
                    status = 200,
                    content = "fallback response",
                )
            }
        }

        test("transient provider failure falls back through the real HTTP container").config(
            enabled = ProviderContainer.enabled,
        ) {
            val response = HttpTestClient.post(
                port = port,
                body = """{"model":"container","messages":[{"role":"user","content":"hello"}]}""",
                requestId = "req-container-fallback",
            )

            response.statusCode() shouldBe HttpStatus.OK.value()
            response.headers().firstValue("X-Request-Id").orElse(null) shouldBe "req-container-fallback"
            response.body() shouldContain "\"content\":\"fallback response\""
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun providerProperties(registry: DynamicPropertyRegistry) {
            ProviderContainer.register(registry)
        }
    }
}
