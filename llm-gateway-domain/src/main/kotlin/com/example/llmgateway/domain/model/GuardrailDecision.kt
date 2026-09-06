package com.example.llmgateway.domain.model

data class GuardrailDecision(
    val allowed: Boolean,
    val reason: String? = null,
) {
    companion object {
        val ALLOWED = GuardrailDecision(allowed = true)
    }
}
