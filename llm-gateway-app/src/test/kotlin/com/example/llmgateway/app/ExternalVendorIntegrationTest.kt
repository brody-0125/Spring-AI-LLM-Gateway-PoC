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
        "gateway.providers.openai.model-group=external-openai",
        "gateway.providers.openrouter.model-group=external-openrouter",
        "gateway.providers.bedrock.enabled=false",
    ],
)
@ActiveProfiles("test")
@Import(TestDistributedStateConfiguration::class)
class OpenAiExternalVendorIntegrationTest : FunSpec() {

    @LocalServerPort
    var port: Int = 0

    init {
        extensions(SpringExtension())
        test("configured OpenAI key completes a request").config(
            enabled = externalVendorTestsEnabled("OPENAI_API_KEY"),
        ) {
            val response = HttpTestClient.post(port, request("external-openai"))
            response.statusCode() shouldBe HttpStatus.OK.value()
            response.body() shouldContain "\"role\":\"assistant\""
        }
    }

}

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "gateway.providers.openai.model-group=external-openai",
        "gateway.providers.openrouter.model-group=external-openrouter",
        "gateway.providers.bedrock.enabled=false",
    ],
)
@ActiveProfiles("test")
@Import(TestDistributedStateConfiguration::class)
class OpenRouterExternalVendorIntegrationTest : FunSpec() {

    @LocalServerPort
    var port: Int = 0

    init {
        extensions(SpringExtension())
        test("configured OpenRouter key completes a request").config(
            enabled = externalVendorTestsEnabled("OPENROUTER_API_KEY"),
        ) {
            val response = HttpTestClient.post(port, request("external-openrouter"))
            response.statusCode() shouldBe HttpStatus.OK.value()
            response.body() shouldContain "\"role\":\"assistant\""
        }
    }

}

@SpringBootTest(
    classes = [GatewayApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "gateway.providers.openai.model-group=external-openai",
        "gateway.providers.openrouter.model-group=external-openrouter",
        "gateway.providers.bedrock.model-group=external-bedrock",
    ],
)
@ActiveProfiles("test")
@Import(TestDistributedStateConfiguration::class)
class BedrockExternalVendorIntegrationTest : FunSpec() {

    @LocalServerPort
    var port: Int = 0

    init {
        extensions(SpringExtension())
        test("configured Bedrock credentials complete a request").config(
            enabled = externalVendorTestsEnabled("GATEWAY_BEDROCK_ENABLED"),
        ) {
            val response = HttpTestClient.post(port, request("external-bedrock"))
            response.statusCode() shouldBe HttpStatus.OK.value()
            response.body() shouldContain "\"role\":\"assistant\""
        }
    }

}

private fun request(modelGroup: String) =
    """{"model":"$modelGroup","messages":[{"role":"user","content":"Reply with one short word: hello"}]}"""

private fun externalVendorTestsEnabled(credentialVariable: String): Boolean =
    System.getenv("RUN_EXTERNAL_VENDOR_TESTS") == "true" &&
        !System.getenv(credentialVariable).isNullOrBlank()
