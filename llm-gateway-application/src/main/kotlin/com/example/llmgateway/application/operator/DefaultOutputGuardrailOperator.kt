package com.example.llmgateway.application.operator

import com.example.llmgateway.application.port.out.OutputGuardrailPort
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.RequestContext

class DefaultOutputGuardrailOperator(
    private val guardrail: OutputGuardrailPort,
    private val errorFactory: GatewayErrorFactory,
    private val maxStreamOutputCharacters: Int = 100_000,
    private val streamInspectionWindowCharacters: Int = 4_096,
) : OutputGuardrailOperator {

    init {
        require(maxStreamOutputCharacters > 0) { "maxStreamOutputCharacters must be positive" }
        require(streamInspectionWindowCharacters > 0) { "streamInspectionWindowCharacters must be positive" }
    }

    override fun inspectComplete(output: String, context: RequestContext) {
        inspect(output, context)
    }

    override fun openStream(context: RequestContext): OutputGuardrailSession =
        DefaultOutputGuardrailSession(
            guardrail = guardrail,
            errorFactory = errorFactory,
            context = context,
            maxOutputCharacters = maxStreamOutputCharacters,
            inspectionWindowCharacters = streamInspectionWindowCharacters,
        )

    private fun inspect(output: String, context: RequestContext) {
        val decision = try {
            guardrail.inspect(output, context)
        } catch (error: GatewayException) {
            throw error
        } catch (error: Exception) {
            throw errorFactory.guardrailUnavailable(context, error)
        }
        if (!decision.allowed) {
            throw errorFactory.guardrailRejected(context, decision, output = true)
        }
    }
}
