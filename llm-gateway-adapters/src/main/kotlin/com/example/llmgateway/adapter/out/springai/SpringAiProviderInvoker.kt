package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.ProviderChunk
import com.example.llmgateway.domain.inference.chat.ProviderResponse
import com.example.llmgateway.domain.routing.Deployment
import com.example.llmgateway.domain.stream.CloseableStream
import com.example.llmgateway.domain.stream.ManagedStream
import org.springframework.ai.chat.model.ChatModel

class SpringAiProviderInvoker(
    private val models: Map<DeploymentId, ChatModel>,
) : ProviderInvokerPort {

    override fun complete(
        deployment: Deployment,
        request: CanonicalChatRequest,
        attempt: AttemptContext,
    ): ProviderResponse {
        return try {
            val model = models[deployment.id]
                ?: throw IllegalStateException("No ChatModel configured for ${deployment.id.value}")
            val prompt = PromptMapper.toPrompt(request, deployment)
            ChatResponseMapper.toProviderResponse(model.call(prompt), deployment.vendor)
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
        attempt: AttemptContext,
    ): CloseableStream<ProviderChunk> {
        return ManagedStream { scope -> sequence {
            try {
                val model = models[deployment.id]
                    ?: throw IllegalStateException("No ChatModel configured for ${deployment.id.value}")
                val prompt = PromptMapper.toPrompt(request, deployment)
                // The SDK stream stays inside the adapter; close cancels its local subscription.
                val responses = scope.own(model.stream(prompt).toStream(1))
                responses.iterator().asSequence().forEach { response ->
                    yield(ChatResponseMapper.toProviderChunk(response, deployment.vendor))
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: java.util.concurrent.CancellationException) {
                throw error
            } catch (error: Exception) {
                throw ProviderFailureMapper.map(error, deployment.vendor)
            }
        } }
    }
}
