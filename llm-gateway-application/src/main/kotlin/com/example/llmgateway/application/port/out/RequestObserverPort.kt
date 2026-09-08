package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RequestOutcome

interface RequestObserverPort {
    fun onStart(context: RequestContext, request: CanonicalChatRequest)

    fun onStop(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome)
}
