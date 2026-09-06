package com.example.llmgateway.domain.model

data class RateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0,
) {
    companion object {
        val ALLOWED = RateLimitDecision(allowed = true)
    }
}
