package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.observation.NoOpObservationContext

object NoOpObservationContextPort : ObservationContextPort {
    override fun capture() = NoOpObservationContext
}
