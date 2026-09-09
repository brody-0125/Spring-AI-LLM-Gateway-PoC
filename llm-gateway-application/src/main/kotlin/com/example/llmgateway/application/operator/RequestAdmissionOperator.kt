package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest

fun interface RequestAdmissionOperator {
    fun execute(request: CanonicalChatRequest, context: RequestContext)
}
