package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.observation.ObservationHandle
import com.example.llmgateway.domain.observation.RequestObservationContext

interface RequestObserverPort {
    fun start(metadata: RequestObservationContext): ObservationHandle<RequestOutcome>
}
