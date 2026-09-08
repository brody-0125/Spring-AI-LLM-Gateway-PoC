package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RequestOutcome

object NoOpRequestObserverPort : RequestObserverPort {
    override fun onStart(context: RequestContext, request: CanonicalChatRequest) = Unit

    override fun onStop(context: RequestContext, request: CanonicalChatRequest, outcome: RequestOutcome) = Unit
}
