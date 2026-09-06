package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.ProviderChunk
import com.example.llmgateway.domain.model.ProviderResponse
import org.springframework.ai.chat.model.ChatModel

class SpringAiProviderInvoker(
    private val models: Map<DeploymentId, ChatModel>,
) : ProviderInvokerPort {

    override fun complete(
        deployment: Deployment,
        request: CanonicalChatRequest,
    ): ProviderResponse {
        val model = models[deployment.id]
            ?: throw IllegalStateException("No ChatModel configured for ${deployment.id.value}")
        val prompt = PromptMapper.toPrompt(request, deployment)
        return try {
            ChatResponseMapper.toProviderResponse(model.call(prompt))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            throw ProviderFailureMapper.map(error, deployment.vendor)
        }
    }

    override fun stream(
        deployment: Deployment,
        request: CanonicalChatRequest,
    ): Sequence<ProviderChunk> {
        val model = models[deployment.id]
            ?: throw IllegalStateException("No ChatModel configured for ${deployment.id.value}")
        val prompt = PromptMapper.toPrompt(request, deployment)
        return sequence {
            try {
                // Spring AI owns the provider-side Flux. The gateway deliberately consumes it
                // as a blocking Iterable on a virtual thread so the application port remains
                // MVC/Sequence based and each element can be flushed by SseEmitter.
                model.stream(prompt).toIterable().forEach { response ->
                    yield(ChatResponseMapper.toProviderChunk(response))
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: Exception) {
                throw ProviderFailureMapper.map(error, deployment.vendor)
            }
        }
    }
}
