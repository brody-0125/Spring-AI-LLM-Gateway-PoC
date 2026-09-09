package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.application.port.`in`.CompleteChatCommandIn
import com.example.llmgateway.application.port.`in`.StreamChatCommandIn
import com.example.llmgateway.application.port.out.ClientAuthenticationPort
import com.example.llmgateway.application.port.out.ObservationContextPort
import com.example.llmgateway.contract.ChatCompletionRequest
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayCompleteEvent
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.observation.NoOpObservationContext
import com.example.llmgateway.domain.observation.withObservationScope
import com.example.llmgateway.domain.stream.CloseableStream
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/v1")
class ChatCompletionController(
    private val completeChatCommandIn: CompleteChatCommandIn,
    private val streamChatCommandIn: StreamChatCommandIn,
    private val clientAuthenticationPort: ClientAuthenticationPort,
    private val observationContext: ObservationContextPort,
    @param:Value("\${gateway.request.timeout:60s}") private val requestTimeout: Duration,
) {

    init {
        require(!requestTimeout.isZero && !requestTimeout.isNegative) { "gateway request timeout must be positive" }
    }

    @PostMapping(
        "/chat/completions",
        produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE],
    )
    fun complete(
        @RequestBody request: ChatCompletionRequest,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader("X-Request-Id", required = false) requestHeader: String?,
        @RequestHeader("traceparent", required = false) traceparent: String?,
        response: HttpServletResponse,
    ): Any {
        val requestId = RequestId(requestHeader?.takeIf(String::isNotBlank) ?: "req_${UUID.randomUUID()}")
        response.setHeader("X-Request-Id", requestId.value)
        val principal = clientAuthenticationPort.authenticate(authorization)
            ?: throw GatewayException(
                GatewayError(
                    type = "authentication_required",
                    code = "CLIENT_UNAUTHORIZED",
                    category = ErrorCategory.ENTITLEMENT,
                    retryable = false,
                    message = "A valid gateway bearer token is required",
                    requestId = requestId,
                ),
            )
        val startedAt = Instant.now()
        val context = RequestContext(
            requestId = requestId,
            caller = principal.caller,
            tenant = principal.tenant,
            traceId = traceId(traceparent),
            startedAt = startedAt,
            deadline = startedAt.plus(requestTimeout),
        )
        val canonicalRequest = ChatCompletionRequestMapper.toCanonical(request)

        return if (canonicalRequest.stream) {
            stream(canonicalRequest, context)
        } else {
            GatewayResponseMapper.toContract(completeChatCommandIn.complete(canonicalRequest, context))
        }
    }

    private fun stream(
        request: CanonicalChatRequest,
        context: RequestContext,
    ): SseEmitter {
        val emitter = SseEmitter(remainingTimeoutMillis(context.deadline))
        val captured = runCatching { observationContext.capture() }.getOrDefault(NoOpObservationContext)
        val worker = AtomicReference<Thread>()
        val activeStream = AtomicReference<CloseableStream<GatewayEvent>?>()
        val cancelled = AtomicBoolean()
        val cancelWorker: () -> Unit = {
            cancelled.set(true)
            worker.get()?.interrupt()
            runCatching { activeStream.get()?.cancel() }
        }
        emitter.onCompletion(cancelWorker)
        emitter.onTimeout {
            cancelWorker()
            emitter.complete()
        }
        emitter.onError {
            cancelWorker()
        }

        val thread = Thread.ofVirtual()
            .name("llm-gateway-sse-${context.executionId.value}")
            .unstarted {
                captured.withObservationScope {
                    try {
                        val stream = streamChatCommandIn.stream(request, context)
                        activeStream.set(stream)
                        var completion: GatewayCompleteEvent? = null
                        stream.use {
                            if (cancelled.get()) return@withObservationScope
                            it.forEach { event ->
                                check(completion == null) { "Unexpected event after stream completion" }
                                if (event is GatewayCompleteEvent) completion = event
                                else emitter.send(SseEventMapper.toSseEvent(event))
                            }
                        }
                        if (!cancelled.get()) {
                            emitter.send(SseEventMapper.toSseEvent(checkNotNull(completion) { "Missing stream completion" }))
                            emitter.send(SseEventMapper.doneEvent())
                        }
                        emitter.complete()
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        emitter.complete()
                    } catch (error: Exception) {
                        if (cancelled.get() || error is java.util.concurrent.CancellationException) {
                            emitter.complete()
                        } else if (error is GatewayException) {
                            runCatching { emitter.send(SseEventMapper.errorEvent(error)) }
                            emitter.complete()
                        } else {
                            emitter.completeWithError(error)
                        }
                    } finally {
                        runCatching { activeStream.getAndSet(null)?.close() }
                    }
                }
            }
        worker.set(thread)
        thread.start()
        return emitter
    }

    private fun remainingTimeoutMillis(deadline: Instant): Long =
        Duration.between(Instant.now(), deadline).toMillis().coerceAtLeast(1)

    private fun traceId(traceparent: String?): String? {
        val parts = traceparent?.split('-') ?: return null
        if (parts.size != 4) return null
        val traceId = parts[1]
        return traceId.takeIf {
            it.length == 32 && it.all { character -> character in "0123456789abcdefABCDEF" }
        }
    }
}
