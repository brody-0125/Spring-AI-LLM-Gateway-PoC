package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest

object NoOpRequestAccountingPort : RequestAccountingPort {
    override fun record(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) = Unit
}
