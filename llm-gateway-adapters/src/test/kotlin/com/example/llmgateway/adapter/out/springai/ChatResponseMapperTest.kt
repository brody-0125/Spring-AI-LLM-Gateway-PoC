package com.example.llmgateway.adapter.out.springai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

class ChatResponseMapperTest : FunSpec({
    test("maps provider response text and usage") {
        val mapped = ChatResponseMapper.toProviderResponse(response())

        mapped.text shouldBe "hello"
        mapped.usage.inputTokens shouldBe 2
        mapped.usage.outputTokens shouldBe 3
        mapped.usage.totalTokens shouldBe 5
        mapped.providerRequestId shouldBe "provider-response-1"
    }

    test("maps streaming finish reason and usage only when present") {
        val mapped = ChatResponseMapper.toProviderChunk(response())

        mapped.text shouldBe "hello"
        mapped.finishReason shouldBe "stop"
        mapped.usage?.totalTokens shouldBe 5
        mapped.providerRequestId shouldBe "provider-response-1"

        val withoutUsage = ChatResponseMapper.toProviderChunk(
            ChatResponse(
                listOf(Generation(AssistantMessage("partial"), ChatGenerationMetadata.builder().build())),
                ChatResponseMetadata.builder().usage(DefaultUsage(0, 0, 0)).build(),
            ),
        )
        withoutUsage.usage shouldBe null
    }
}) {
    companion object {
        private fun response() = ChatResponse(
            listOf(
                Generation(
                    AssistantMessage("hello"),
                    ChatGenerationMetadata.builder().finishReason("stop").build(),
                ),
            ),
            ChatResponseMetadata.builder().id("provider-response-1").usage(DefaultUsage(2, 3, 5)).build(),
        )
    }
}
