package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.contract.ChatCompletionRequest
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.CanonicalMessage
import com.example.llmgateway.domain.model.CanonicalOptions
import java.util.Locale

internal object ChatCompletionRequestMapper {
    fun toCanonical(request: ChatCompletionRequest): CanonicalChatRequest {
        val temperature = request.temperature
        val maxTokens = request.maxTokens
        val maxCompletionTokens = request.maxCompletionTokens
        val topP = request.topP
        require(request.model.isNotBlank()) { "model must not be blank" }
        require(request.messages.isNotEmpty()) { "messages must not be empty" }
        require(temperature == null || temperature in 0.0..2.0) {
            "temperature must be between 0 and 2"
        }
        require(topP == null || topP in 0.0..1.0) {
            "top_p must be between 0 and 1"
        }
        require(maxTokens == null || maxTokens > 0) { "max_tokens must be positive" }
        require(maxCompletionTokens == null || maxCompletionTokens > 0) {
            "max_completion_tokens must be positive"
        }
        require(maxTokens == null || maxCompletionTokens == null) {
            "max_tokens and max_completion_tokens must not be used together"
        }

        return CanonicalChatRequest(
            modelGroup = ModelGroup(request.model),
            messages = request.messages.map { message ->
                CanonicalMessage(
                    role = when (message.role.lowercase(Locale.ROOT)) {
                        "system" -> MessageRole.SYSTEM
                        "user" -> MessageRole.USER
                        "assistant" -> MessageRole.ASSISTANT
                        else -> throw IllegalArgumentException("unsupported message role: ${message.role}")
                    },
                    content = message.content,
                )
            },
            options = CanonicalOptions(
                temperature = temperature,
                maxTokens = maxTokens,
                maxCompletionTokens = maxCompletionTokens,
                topP = topP,
                stop = request.stop,
            ),
            stream = request.stream,
        )
    }
}
