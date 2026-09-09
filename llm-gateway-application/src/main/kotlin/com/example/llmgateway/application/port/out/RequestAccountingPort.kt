package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest

interface RequestAccountingPort {
    /** Persist execution completion before success is exposed; usage projection is a separate consumer. */
    fun record(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome)
}
