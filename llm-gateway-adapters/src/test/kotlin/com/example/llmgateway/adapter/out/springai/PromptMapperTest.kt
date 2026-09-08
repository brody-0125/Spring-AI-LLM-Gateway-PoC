package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.CanonicalMessage
import com.example.llmgateway.domain.model.CanonicalOptions
import com.example.llmgateway.domain.model.Deployment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.ai.bedrock.converse.BedrockChatOptions
import org.springframework.ai.openai.OpenAiChatOptions

class PromptMapperTest : FunSpec({
    val request = CanonicalChatRequest(
        modelGroup = ModelGroup("default"),
        messages = listOf(
            CanonicalMessage(MessageRole.SYSTEM, "system"),
            CanonicalMessage(MessageRole.USER, "hello"),
        ),
        options = CanonicalOptions(
            temperature = 0.3,
            maxCompletionTokens = 128,
            topP = 0.7,
            stop = listOf("END"),
        ),
    )

    test("maps common options to OpenAI-compatible deployments and always requests usage") {
        val options = PromptMapper.toPrompt(request, deployment(Dialect.OPENAI)).options as OpenAiChatOptions

        options.model shouldBe "gpt-test"
        options.temperature shouldBe 0.3
        options.maxCompletionTokens shouldBe 128
        options.maxTokens shouldBe null
        options.topP shouldBe 0.7
        options.stop shouldBe listOf("END")
        options.streamOptions?.includeUsage() shouldBe true
    }

    test("maps only common options to Bedrock Converse") {
        val options = PromptMapper.toPrompt(request, deployment(Dialect.BEDROCK_CONVERSE)).options as BedrockChatOptions

        options.model shouldBe "gpt-test"
        options.temperature shouldBe 0.3
        options.maxTokens shouldBe 128
        options.topP shouldBe 0.7
        options.stopSequences shouldBe listOf("END")
    }
}) {
    companion object {
        private fun deployment(dialect: Dialect) = Deployment(
            id = DeploymentId("test-deployment"),
            vendor = if (dialect == Dialect.BEDROCK_CONVERSE) Vendor.AWS_BEDROCK else Vendor.OPENAI,
            dialect = dialect,
            modelGroup = ModelGroup("default"),
            model = "gpt-test",
        )
    }
}
