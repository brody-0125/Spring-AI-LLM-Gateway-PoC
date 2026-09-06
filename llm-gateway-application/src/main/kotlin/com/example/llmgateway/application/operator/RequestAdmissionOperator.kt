package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.RequestContext

fun interface RequestAdmissionOperator {
    fun execute(request: CanonicalChatRequest, context: RequestContext)
}
