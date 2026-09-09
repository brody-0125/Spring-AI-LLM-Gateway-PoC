package com.example.llmgateway.domain.policy

data class GuardrailDecision(
    val allowed: Boolean,
    val reason: String? = null,
    val code: String = "POLICY_BLOCKED",
) {
    companion object {
        val ALLOWED = GuardrailDecision(allowed = true)
    }
}
