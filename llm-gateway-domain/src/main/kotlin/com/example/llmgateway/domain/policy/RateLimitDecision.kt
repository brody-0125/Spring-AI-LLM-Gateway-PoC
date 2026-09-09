package com.example.llmgateway.domain.policy

data class RateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0,
    val backendAvailable: Boolean = true,
) {
    companion object {
        val ALLOWED = RateLimitDecision(allowed = true)
    }
}
