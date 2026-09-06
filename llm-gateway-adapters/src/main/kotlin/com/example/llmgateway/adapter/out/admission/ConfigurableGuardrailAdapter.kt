package com.example.llmgateway.adapter.out.admission

import com.example.llmgateway.application.port.out.GuardrailPort
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GuardrailDecision
import com.example.llmgateway.domain.model.RequestContext

class ConfigurableGuardrailAdapter(
    private val enabled: Boolean,
    private val maxInputCharacters: Int,
    blockedPhrases: List<String>,
) : GuardrailPort {

    private val blockedPhrases = blockedPhrases
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)

    init {
        require(maxInputCharacters > 0) { "maxInputCharacters must be positive" }
    }

    override fun inspect(request: CanonicalChatRequest, context: RequestContext): GuardrailDecision {
        if (!enabled) return GuardrailDecision.ALLOWED

        val input = request.messages.joinToString("\n") { it.content }
        if (input.length > maxInputCharacters) {
            return GuardrailDecision(false, "The request exceeds the gateway input limit")
        }

        val normalized = input.lowercase()
        val blocked = blockedPhrases.firstOrNull(normalized::contains)
        return if (blocked == null) {
            GuardrailDecision.ALLOWED
        } else {
            GuardrailDecision(false, "The request was rejected by a configured content guardrail")
        }
    }
}
