package com.example.llmgateway.application.operator

import com.example.llmgateway.application.port.out.OutputGuardrailPort
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.GuardrailDecision
import com.example.llmgateway.domain.model.RequestContext

class DefaultOutputGuardrailSession(
    private val guardrail: OutputGuardrailPort,
    private val errorFactory: GatewayErrorFactory,
    private val context: RequestContext,
    private val maxOutputCharacters: Int,
    private val inspectionWindowCharacters: Int,
) : OutputGuardrailSession {

    private var outputCharacters = 0L
    private var inspectionWindow = ""

    override fun inspect(chunk: String) {
        outputCharacters += chunk.length.toLong()
        if (outputCharacters > maxOutputCharacters) {
            throw errorFactory.guardrailRejected(
                context,
                GuardrailDecision(false, code = "OUTPUT_TOO_LARGE"),
                output = true,
            )
        }

        val candidate = if (chunk.length >= inspectionWindowCharacters) {
            chunk
        } else {
            inspectionWindow + chunk
        }
        val decision = try {
            guardrail.inspect(candidate, context)
        } catch (error: GatewayException) {
            throw error
        } catch (error: Exception) {
            throw errorFactory.guardrailUnavailable(context, error)
        }
        if (!decision.allowed) {
            throw errorFactory.guardrailRejected(context, decision, output = true)
        }
        inspectionWindow = candidate.takeLast(inspectionWindowCharacters)
    }
}
