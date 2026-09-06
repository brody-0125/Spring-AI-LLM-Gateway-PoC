package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.contract.ChatCompletionRequest
import com.example.llmgateway.contract.ChatMessageDto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChatCompletionRequestMapperTest : FunSpec({
    test("maps only the common provider request options") {
        val request = ChatCompletionRequest(
            model = "default",
            messages = listOf(ChatMessageDto("system", "system"), ChatMessageDto("user", "hello")),
            temperature = 0.4,
            maxCompletionTokens = 128,
            topP = 0.8,
            stop = listOf("END"),
            stream = true,
        )

        val canonical = ChatCompletionRequestMapper.toCanonical(request)

        canonical.modelGroup.value shouldBe "default"
        canonical.messages.size shouldBe 2
        canonical.options.temperature shouldBe 0.4
        canonical.options.maxCompletionTokens shouldBe 128
        canonical.options.topP shouldBe 0.8
        canonical.options.stop shouldBe listOf("END")
        canonical.stream shouldBe true
    }

    test("rejects invalid range and conflicting token options") {
        shouldThrow<IllegalArgumentException> {
            ChatCompletionRequestMapper.toCanonical(
                ChatCompletionRequest("default", listOf(ChatMessageDto("user", "hello")), temperature = 2.1),
            )
        }
        shouldThrow<IllegalArgumentException> {
            ChatCompletionRequestMapper.toCanonical(
                ChatCompletionRequest(
                    "default",
                    listOf(ChatMessageDto("user", "hello")),
                    maxTokens = 1,
                    maxCompletionTokens = 1,
                ),
            )
        }
    }

    test("rejects unsupported message roles and empty model groups") {
        shouldThrow<IllegalArgumentException> {
            ChatCompletionRequestMapper.toCanonical(
                ChatCompletionRequest("default", listOf(ChatMessageDto("tool", "hello"))),
            )
        }
        shouldThrow<IllegalArgumentException> {
            ChatCompletionRequestMapper.toCanonical(
                ChatCompletionRequest(" ", listOf(ChatMessageDto("user", "hello"))),
            )
        }
    }
})
