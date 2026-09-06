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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("test")
@Import(TestDistributedStateConfiguration::class)
class SpringAiProviderStreamingIntegrationTest : FunSpec() {

    @LocalServerPort
    var port: Int = 0

    init {
        extensions(SpringExtension())
        beforeSpec {
            if (ProviderContainer.enabled) {
                ProviderContainer.resetMappings()
                ProviderContainer.stubStreamingAuthorizationResponse(
                    authorization = ProviderContainer.primaryKey,
                    content = "container stream",
                )
            }
        }

        test("Spring AI streaming adapter forwards provider chunks as SSE frames").config(
            enabled = ProviderContainer.enabled,
        ) {
            val response = HttpTestClient.post(
                port = port,
                body = """{"model":"container","stream":true,"messages":[{"role":"user","content":"hello"}]}""",
                requestId = "req-container-stream",
                accept = MediaType.TEXT_EVENT_STREAM_VALUE,
            )

            response.statusCode() shouldBe HttpStatus.OK.value()
            response.headers().firstValue("Content-Type").orElse("") shouldContain "text/event-stream"
            response.body() shouldContain "container stream"
            response.body() shouldContain "[DONE]"
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
