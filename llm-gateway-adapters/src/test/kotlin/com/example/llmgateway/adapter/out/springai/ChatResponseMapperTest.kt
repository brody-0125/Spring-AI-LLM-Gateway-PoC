package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.accounting.CostCalculator
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.openai.models.completions.CompletionUsage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.metadata.EmptyUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage

class ChatResponseMapperTest : FunSpec({
    test("maps provider response text and usage") {
        val mapped = ChatResponseMapper.toProviderResponse(response(), Vendor.OPENAI)

        mapped.text shouldBe "hello"
        mapped.usage.inputTokens shouldBe 2
        mapped.usage.outputTokens shouldBe 3
        mapped.usage.totalTokens shouldBe 5
        mapped.providerRequestId shouldBe "provider-response-1"
    }

    test("maps streaming finish reason and usage only when present") {
        val mapped = ChatResponseMapper.toProviderChunk(response(), Vendor.OPENAI)

        mapped.text shouldBe "hello"
        mapped.finishReason shouldBe "stop"
        mapped.usage?.totalTokens shouldBe 5
        mapped.providerRequestId shouldBe "provider-response-1"

        val withoutUsage = ChatResponseMapper.toProviderChunk(
            ChatResponse(
                listOf(Generation(AssistantMessage("partial"), ChatGenerationMetadata.builder().build())),
                ChatResponseMetadata.builder().usage(EmptyUsage()).build(),
            ),
            Vendor.OPENAI,
        )
        withoutUsage.usage shouldBe null
    }
    test("explicit zero usage is reported in both JSON and streaming rather than discarded") {
        val response = ChatResponse(emptyList(), ChatResponseMetadata.builder().usage(DefaultUsage(0, 0, 0)).build())
        Vendor.entries.forEach { vendor ->
            ChatResponseMapper.toProviderResponse(response, vendor).usage.available shouldBe true
            ChatResponseMapper.toProviderChunk(response, vendor).usage?.totalTokens shouldBe 0L
            ChatResponseMapper.toProviderResponse(ChatResponse(emptyList()), vendor).usage.available shouldBe false
        }
    }

    test("OpenAI-compatible native reasoning and cache detail are included once") {
        val native = CompletionUsage.builder().promptTokens(100L).completionTokens(40L).totalTokens(140L)
            .promptTokensDetails(CompletionUsage.PromptTokensDetails.builder().cachedTokens(30L).build())
            .completionTokensDetails(CompletionUsage.CompletionTokensDetails.builder().reasoningTokens(25L).build())
            .build()
        val response = ChatResponse(emptyList(), ChatResponseMetadata.builder()
            .usage(DefaultUsage(100, 40, 140, native, 30L, null)).build())
        listOf(Vendor.OPENAI, Vendor.OPENROUTER).forEach { vendor ->
            val usage = ChatResponseMapper.toProviderResponse(response, vendor).usage
            usage.inputTokens shouldBe 100L
            usage.regularInputTokens shouldBe 70L
            usage.reasoningOutputTokens shouldBe 25L
            usage.outputTokens shouldBe 40L
            ChatResponseMapper.toProviderChunk(response, vendor).usage shouldBe usage
            val cost = CostCalculator().calculate(usage,
                PricingSnapshot("test", "1".toBigDecimal(), "2".toBigDecimal(), "0.5".toBigDecimal()))
            cost.usd shouldBe "165.000000000000000000".toBigDecimal()
        }
    }

    test("Bedrock uncached input and cache counters normalize before pricing and public totals") {
        val native = TokenUsage.builder().inputTokens(10).outputTokens(5).totalTokens(15)
            .cacheReadInputTokens(100).cacheWriteInputTokens(20).build()
        val response = ChatResponse(emptyList(), ChatResponseMetadata.builder()
            .usage(DefaultUsage(10, 5, 15, native, 100L, 20L)).build())
        val usage = ChatResponseMapper.toProviderResponse(response, Vendor.AWS_BEDROCK).usage
        usage.inputTokens shouldBe 130L
        usage.regularInputTokens shouldBe 10L
        usage.totalTokens shouldBe 135L
        ChatResponseMapper.toProviderChunk(response, Vendor.AWS_BEDROCK).usage shouldBe usage
        val cost = CostCalculator().calculate(usage, PricingSnapshot("test",
            "1".toBigDecimal(), "2".toBigDecimal(), "0.1".toBigDecimal(), "0.5".toBigDecimal()))
        cost.warnings.isEmpty() shouldBe true
        cost.usd shouldBe "40.000000000000000000".toBigDecimal()
    }

    test("invalid provider counts cannot become valid through cache normalization") {
        val native = TokenUsage.builder().inputTokens(-1).outputTokens(5).totalTokens(4)
            .cacheReadInputTokens(100).build()
        val response = ChatResponse(emptyList(), ChatResponseMetadata.builder()
            .usage(DefaultUsage(-1, 5, 4, native, 100L, null)).build())
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            ChatResponseMapper.toProviderResponse(response, Vendor.AWS_BEDROCK)
        }
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
