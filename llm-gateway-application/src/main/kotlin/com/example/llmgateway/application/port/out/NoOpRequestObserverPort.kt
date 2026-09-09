package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.observation.NoOpObservationHandle
import com.example.llmgateway.domain.observation.RequestObservationContext

object NoOpRequestObserverPort : RequestObserverPort {
    override fun start(metadata: RequestObservationContext) = NoOpObservationHandle
}
