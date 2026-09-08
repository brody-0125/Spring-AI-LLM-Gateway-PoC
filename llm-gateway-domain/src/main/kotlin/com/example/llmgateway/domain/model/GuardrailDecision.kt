package com.example.llmgateway.domain.model

data class GuardrailDecision(
    val allowed: Boolean,
    val reason: String? = null,
    val code: String = "POLICY_BLOCKED",
) {
    companion object {
        val ALLOWED = GuardrailDecision(allowed = true)
    }
}
