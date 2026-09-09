package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.springai.SpringAiProviderInvoker
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ExecutionId
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.routing.Deployment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

class SpringAiStreamLifecycleTest : FunSpec({
    val deployment = Deployment(DeploymentId("stream-test"), Vendor.OPENAI, Dialect.OPENAI, ModelGroup("default"), "test")
    val request = CanonicalChatRequest(ModelGroup("default"), emptyList(), stream = true)
    val attempt = AttemptContext(RequestId("test"), AttemptId("test"), 1, deployment, ExecutionId.newId(), AttemptKind.INITIAL)

    test("closing after one chunk cancels the actual SDK subscription exactly once") {
        val cancelled = AtomicInteger()
        val model = object : ChatModel {
            override fun call(prompt: Prompt): ChatResponse = error("not a blocking request")
            override fun stream(prompt: Prompt): Flux<ChatResponse> =
                Flux.just(ChatResponse(emptyList())).concatWith(Flux.never()).doOnCancel { cancelled.incrementAndGet() }
        }
        val stream = SpringAiProviderInvoker(mapOf(deployment.id to model)).stream(deployment, request, attempt)
        stream.use { it.take(1).toList().size shouldBe 1 }
        stream.close()
        cancelled.get() shouldBe 1
    }

    test("cancelling a blocked SDK pull releases the local reader and subscription") {
        val subscribed = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val model = object : ChatModel {
            override fun call(prompt: Prompt): ChatResponse = error("not a blocking request")
            override fun stream(prompt: Prompt): Flux<ChatResponse> =
                Flux.never<ChatResponse>().doOnSubscribe { subscribed.countDown() }
                    .doOnCancel { cancelled.incrementAndGet() }
        }
        val stream = SpringAiProviderInvoker(mapOf(deployment.id to model)).stream(deployment, request, attempt)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val reading = executor.submit { runCatching { stream.toList() } }
            try {
                check(subscribed.await(5, TimeUnit.SECONDS))
                stream.cancel()
                reading.get(5, TimeUnit.SECONDS)
            } finally {
                stream.close()
            }
        }
        cancelled.get() shouldBe 1
    }
})
