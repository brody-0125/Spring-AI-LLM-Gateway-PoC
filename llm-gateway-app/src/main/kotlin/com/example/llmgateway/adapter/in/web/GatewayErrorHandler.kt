package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.contract.ErrorResponseDto
import com.example.llmgateway.contract.GatewayErrorDto
import com.example.llmgateway.domain.model.ErrorCategory
import com.example.llmgateway.domain.model.GatewayException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
class GatewayErrorHandler {

    @ExceptionHandler(GatewayException::class)
    fun handle(error: GatewayException): ResponseEntity<ErrorResponseDto> {
        val status = when (error.error.category) {
            ErrorCategory.CALLER_FIXABLE -> 400
            ErrorCategory.ENTITLEMENT -> if (error.error.type == "authentication_required") 401 else 403
            ErrorCategory.TRANSIENT -> if (
                error.error.type == "rate_limited" || error.error.type == "gateway_rate_limited"
            ) 429 else 503
            ErrorCategory.GATEWAY_FAULT -> 500
        }
        val response = ResponseEntity.status(status)
            .header("X-Request-Id", error.error.requestId.value)
            .apply {
                error.error.retryAfterSeconds?.let { header("Retry-After", it.toString()) }
            }
        return response.body(ErrorResponseDto(error.error.toContract()))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalid(error: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ErrorResponseDto> =
        badRequest(error.message ?: "Invalid request", request.requestId())

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(error: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ErrorResponseDto> =
        badRequest("Invalid request body", request.requestId())

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(error: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponseDto> =
        badRequest("Invalid request", request.requestId())

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponseDto> =
        ResponseEntity.internalServerError()
            .header("X-Request-Id", request.requestId())
            .body(
                ErrorResponseDto(
                    GatewayErrorDto(
                        type = "gateway_error",
                        message = "The gateway failed to process the request",
                        retryable = true,
                        requestId = request.requestId(),
                    ),
                ),
            )

    private fun badRequest(message: String, requestId: String): ResponseEntity<ErrorResponseDto> =
        ResponseEntity.badRequest()
            .header("X-Request-Id", requestId)
            .body(
                ErrorResponseDto(
                    GatewayErrorDto(
                        type = "invalid_request",
                        message = message,
                        retryable = false,
                        requestId = requestId,
                    ),
                ),
            )
}

private fun HttpServletRequest.requestId(): String =
    getHeader("X-Request-Id")?.takeIf(String::isNotBlank) ?: "req_${UUID.randomUUID()}"

private fun com.example.llmgateway.domain.model.GatewayError.toContract() = GatewayErrorDto(
    type = type,
    message = message,
    retryable = retryable,
    requestId = requestId.value,
    retryAfterSeconds = retryAfterSeconds,
)
