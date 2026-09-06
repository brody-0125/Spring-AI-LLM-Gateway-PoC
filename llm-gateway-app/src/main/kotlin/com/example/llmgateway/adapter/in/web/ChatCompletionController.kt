package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.application.port.`in`.ChatCompletionCommandIn
import com.example.llmgateway.application.port.`in`.ChatCompletionQueryIn
import com.example.llmgateway.application.port.out.ClientAuthenticationPort
import com.example.llmgateway.contract.ChatCompletionRequest
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.ErrorCategory
import com.example.llmgateway.domain.model.GatewayError
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.RequestContext
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.beans.factory.annotation.Value
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RestController
@RequestMapping("/v1")
class ChatCompletionController(
    private val chatCompletionQueryIn: ChatCompletionQueryIn,
    private val chatCompletionCommandIn: ChatCompletionCommandIn,
    private val clientAuthenticationPort: ClientAuthenticationPort,
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
                    category = ErrorCategory.ENTITLEMENT,
                    retryable = false,
                    message = "A valid gateway bearer token is required",
                    requestId = requestId,
                ),
            )
        val context = RequestContext(
            requestId = requestId,
            caller = principal.caller,
            tenant = principal.tenant,
            traceId = traceId(traceparent),
            deadline = Instant.now().plus(requestTimeout),
        )
        val canonicalRequest = ChatCompletionRequestMapper.toCanonical(request)

        return if (canonicalRequest.stream) {
            stream(canonicalRequest, context)
        } else {
            GatewayResponseMapper.toContract(chatCompletionQueryIn.complete(canonicalRequest, context))
        }
    }

    private fun stream(
        request: CanonicalChatRequest,
        context: RequestContext,
    ): SseEmitter {
        val emitter = SseEmitter(remainingTimeoutMillis(context.deadline))
        val worker = AtomicReference<Thread>()
        val cancelWorker: () -> Unit = {
            worker.get()?.interrupt()
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
            .name("llm-gateway-sse-${context.requestId.value}")
            .start {
                try {
                    chatCompletionCommandIn.stream(request, context).forEach { event ->
                        emitter.send(SseEventMapper.toSseEvent(event))
                    }
                    emitter.send(SseEventMapper.doneEvent())
                    emitter.complete()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    emitter.complete()
                } catch (error: Exception) {
                    if (error is GatewayException) {
                        runCatching { emitter.send(SseEventMapper.errorEvent(error)) }
                        emitter.complete()
                    } else {
                        emitter.completeWithError(error)
                    }
                }
            }
        worker.set(thread)
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
