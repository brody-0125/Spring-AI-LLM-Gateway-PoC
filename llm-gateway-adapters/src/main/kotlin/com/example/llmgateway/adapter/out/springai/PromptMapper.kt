package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.MessageRole
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.CanonicalMessage
import com.example.llmgateway.domain.model.Deployment
import org.springframework.ai.bedrock.converse.BedrockChatOptions
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions

internal object PromptMapper {
    fun toPrompt(request: CanonicalChatRequest, deployment: Deployment): Prompt {
        val messages = request.messages.map(::toSpringMessage)
        val options = when (deployment.dialect) {
            Dialect.OPENAI, Dialect.OPENAI_COMPATIBLE_OPENROUTER -> openAiOptions(request, deployment)
            Dialect.BEDROCK_CONVERSE -> bedrockOptions(request, deployment)
        }
        return Prompt(messages, options)
    }

    private fun toSpringMessage(message: CanonicalMessage): Message = when (message.role) {
        MessageRole.SYSTEM -> SystemMessage(message.content)
        MessageRole.USER -> UserMessage(message.content)
        MessageRole.ASSISTANT -> AssistantMessage(message.content)
    }

    private fun openAiOptions(request: CanonicalChatRequest, deployment: Deployment): OpenAiChatOptions {
        val builder = OpenAiChatOptions.builder().model(deployment.model)
        request.options.temperature?.let(builder::temperature)
        (request.options.maxCompletionTokens ?: request.options.maxTokens)?.let(builder::maxTokens)
        request.options.topP?.let(builder::topP)
        request.options.stop?.let(builder::stop)
        // Usage is required by the gateway for cost tracking, so it is not caller-controlled.
        builder.streamUsage(true)
        return builder.build()
    }

    private fun bedrockOptions(request: CanonicalChatRequest, deployment: Deployment): BedrockChatOptions {
        val builder = BedrockChatOptions.builder().model(deployment.model)
        request.options.temperature?.let(builder::temperature)
        (request.options.maxCompletionTokens ?: request.options.maxTokens)?.let(builder::maxTokens)
        request.options.topP?.let(builder::topP)
        request.options.stop?.let(builder::stopSequences)
        return builder.build()
    }
}
