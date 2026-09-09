package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.application.port.out.ObservationContextPort
import com.example.llmgateway.domain.observation.ObservationContext
import io.micrometer.observation.ObservationRegistry

class MicrometerObservationContextAdapter(
    private val registry: ObservationRegistry,
) : ObservationContextPort {
    override fun capture(): ObservationContext = MicrometerObservationContext(registry, registry.currentObservation)
}
