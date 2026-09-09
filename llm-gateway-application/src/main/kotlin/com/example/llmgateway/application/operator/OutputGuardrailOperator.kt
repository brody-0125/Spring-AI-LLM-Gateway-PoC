package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.execution.RequestContext

interface OutputGuardrailOperator {
    fun inspectComplete(output: String, context: RequestContext)

    fun openStream(context: RequestContext): OutputGuardrailSession
}
