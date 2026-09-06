package com.example.llmgateway.app

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal object ProviderContainer {

    const val primaryKey = "container-primary-key"
    const val fallbackKey = "container-fallback-key"
    val enabled: Boolean = System.getenv("RUN_TESTCONTAINERS") == "true"

    private const val wireMockPort = 8080
    private val httpClient = HttpClient.newHttpClient()
    private val wireMock: GenericContainer<*> = GenericContainer<Nothing>("wiremock/wiremock:3.13.1").apply {
        withExposedPorts(wireMockPort)
        waitingFor(Wait.forHttp("/__admin/mappings").forPort(wireMockPort).forStatusCode(200))
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    fun register(registry: DynamicPropertyRegistry) {
        if (!enabled) return

        ensureStarted()
        registry.add("gateway.providers.openai.api-key") { primaryKey }
        registry.add("gateway.providers.openai.model") { "wiremock-model" }
        registry.add("gateway.providers.openai.model-group") { "container" }
        registry.add("gateway.providers.openai.base-url") { baseUrl() + "/v1" }
        registry.add("gateway.providers.openrouter.api-key") { fallbackKey }
        registry.add("gateway.providers.openrouter.model") { "wiremock-model" }
        registry.add("gateway.providers.openrouter.model-group") { "container" }
        registry.add("gateway.providers.openrouter.base-url") { baseUrl() + "/v1" }
        registry.add("gateway.providers.bedrock.enabled") { "false" }
    }

    fun resetMappings() {
        request("/__admin/mappings/reset", "")
    }

    fun stubAuthorizationResponse(
        authorization: String,
        status: Int,
        content: String,
    ) {
        val mapping = """
            {
              "request": {
                "method": "POST",
                "urlPath": "/v1/chat/completions",
                "headers": {
                  "Authorization": {"equalTo": "Bearer $authorization"}
                }
              },
              "response": {
                "status": $status,
                "headers": {"Content-Type": "application/json"},
                "jsonBody": {
                  "id": "wiremock-$status",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "wiremock-model",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "$content"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3}
                }
              }
            }
        """.trimIndent()
        request("/__admin/mappings", mapping)
    }

    fun stubStreamingAuthorizationResponse(
        authorization: String,
        content: String,
    ) {
        val mapping = """
            {
              "request": {
                "method": "POST",
                "urlPath": "/v1/chat/completions",
                "headers": {
                  "Authorization": {"equalTo": "Bearer $authorization"}
                }
              },
              "response": {
                "status": 200,
                "headers": {"Content-Type": "text/event-stream"},
                "body": "data: {\"id\":\"wiremock-stream\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"wiremock-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"$content\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"wiremock-stream\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"wiremock-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
              }
            }
        """.trimIndent()
        request("/__admin/mappings", mapping)
    }

    private fun ensureStarted() {
        synchronized(this) {
            if (!wireMock.isRunning) wireMock.start()
        }
    }

    private fun baseUrl(): String = "http://${wireMock.host}:${wireMock.getMappedPort(wireMockPort)}"

    private fun stop() {
        if (wireMock.isRunning) wireMock.stop()
    }

    private fun request(path: String, body: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "WireMock admin request failed: ${response.statusCode()} ${response.body()}"
        }
        return response.body()
    }
}
