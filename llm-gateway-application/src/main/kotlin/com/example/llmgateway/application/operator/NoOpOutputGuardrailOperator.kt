package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.model.RequestContext

object NoOpOutputGuardrailOperator : OutputGuardrailOperator {
    override fun inspectComplete(output: String, context: RequestContext) = Unit

    override fun openStream(context: RequestContext): OutputGuardrailSession = OutputGuardrailSession { }
}
