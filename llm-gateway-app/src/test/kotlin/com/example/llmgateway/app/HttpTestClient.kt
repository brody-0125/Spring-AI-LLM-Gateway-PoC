package com.example.llmgateway.app

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal object HttpTestClient {
    private val client = HttpClient.newBuilder().build()

    fun post(
        port: Int,
        body: String,
        requestId: String? = null,
        accept: String? = null,
        authorization: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        requestId?.let { builder.header("X-Request-Id", it) }
        accept?.let { builder.header("Accept", it) }
        authorization?.let { builder.header("Authorization", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun get(port: Int, path: String): HttpResponse<String> = getWithAuthorization(port, path, null)

    fun getWithAuthorization(
        port: Int,
        path: String,
        authorization: String?,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .GET()
        authorization?.let { builder.header("Authorization", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
