package com.example.llmgateway.adapter.out.admission

import com.example.llmgateway.application.port.out.InputGuardrailPort
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GuardrailDecision
import com.example.llmgateway.domain.model.RequestContext
import java.util.Locale

class ConfigurableInputGuardrailAdapter(
    private val enabled: Boolean,
    private val maxInputCharacters: Int,
    blockedPhrases: List<String>,
) : InputGuardrailPort {

    private val blockedPhrases = blockedPhrases
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { it.lowercase(Locale.ROOT) }

    init {
        require(maxInputCharacters > 0) { "maxInputCharacters must be positive" }
    }

    override fun inspect(request: CanonicalChatRequest, context: RequestContext): GuardrailDecision {
        if (!enabled) return GuardrailDecision.ALLOWED

        val input = request.messages.joinToString("\n") { it.content }
        if (input.length > maxInputCharacters) {
            return GuardrailDecision(false, "The request exceeds the gateway input limit", "INPUT_TOO_LARGE")
        }

        val normalized = input.lowercase(Locale.ROOT)
        return if (blockedPhrases.any(normalized::contains)) {
            GuardrailDecision(false, "The request was rejected by a configured content guardrail", "INPUT_POLICY_BLOCKED")
        } else {
            GuardrailDecision.ALLOWED
        }
    }
}
