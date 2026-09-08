package com.example.llmgateway.domain.model
import com.example.llmgateway.core.primitive.RequestId



data class GatewayError(
    val type: String,
    val code: String,
    val category: ErrorCategory,
    val retryable: Boolean,
    val message: String,
    val requestId: RequestId,
    val retryAfterSeconds: Long? = null,
)
