package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RequestOutcome

object NoOpRequestAccountingPort : RequestAccountingPort {
    override fun record(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) = Unit
}
