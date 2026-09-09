package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.contract.ChatChunkChoiceDto
import com.example.llmgateway.contract.ChatCompletionChunkDto
import com.example.llmgateway.contract.ChatDeltaDto
import com.example.llmgateway.contract.ChatMessageDto
import com.example.llmgateway.contract.ErrorResponseDto
import com.example.llmgateway.contract.GatewayErrorDto
import com.example.llmgateway.contract.UsageDto
import com.example.llmgateway.domain.inference.chat.GatewayCompleteEvent
import com.example.llmgateway.domain.inference.chat.GatewayDeltaEvent
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

internal object SseEventMapper {
    fun toSseEvent(event: GatewayEvent): SseEmitter.SseEventBuilder = when (event) {
        is GatewayDeltaEvent -> SseEmitter.event()
            .data(
                ChatCompletionChunkDto(
                    id = event.id,
                    created = event.createdAtEpochSeconds,
                    model = event.model,
                    choices = listOf(
                        ChatChunkChoiceDto(
                            index = 0,
                            delta = ChatDeltaDto(role = "assistant", content = event.text),
                        ),
                    ),
                ),
            )
        is GatewayCompleteEvent -> SseEmitter.event()
            .data(
                ChatCompletionChunkDto(
                    id = event.id,
                    created = event.createdAtEpochSeconds,
                    model = event.model,
                    choices = listOf(
                        ChatChunkChoiceDto(
                            index = 0,
                            delta = ChatDeltaDto(content = ""),
                            finishReason = event.finishReason,
                        ),
                    ),
                    usage = event.usage.toUsageDtoOrNull(),
                ),
            )
    }

    fun doneEvent(): SseEmitter.SseEventBuilder = SseEmitter.event().data("[DONE]")

    fun errorEvent(error: com.example.llmgateway.domain.error.GatewayException): SseEmitter.SseEventBuilder =
        SseEmitter.event().name("error").data(
            ErrorResponseDto(
                GatewayErrorDto(
                    type = error.error.type,
                    code = error.error.code,
                    message = error.error.message,
                    retryable = error.error.retryable,
                    requestId = error.error.requestId.value,
                    retryAfterSeconds = error.error.retryAfterSeconds,
                ),
            ),
        )
}
