package com.example.llmgateway.adapter.out.admission

import com.example.llmgateway.application.port.out.OutputGuardrailPort
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.policy.GuardrailDecision
import java.util.Locale

class ConfigurableOutputGuardrailAdapter(
    private val enabled: Boolean,
    private val maxOutputCharacters: Int,
    blockedPhrases: List<String>,
) : OutputGuardrailPort {

    private val blockedPhrases = blockedPhrases
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { it.lowercase(Locale.ROOT) }

    init {
        require(maxOutputCharacters > 0) { "maxOutputCharacters must be positive" }
    }

    override fun inspect(output: String, context: RequestContext): GuardrailDecision {
        if (!enabled) return GuardrailDecision.ALLOWED
        if (output.length > maxOutputCharacters) {
            return GuardrailDecision(false, "The model response exceeds the gateway output limit", "OUTPUT_TOO_LARGE")
        }

        val normalized = output.lowercase(Locale.ROOT)
        return if (blockedPhrases.any(normalized::contains)) {
            GuardrailDecision(false, "The model response was rejected by a configured content guardrail", "OUTPUT_POLICY_BLOCKED")
        } else {
            GuardrailDecision.ALLOWED
        }
    }
}
