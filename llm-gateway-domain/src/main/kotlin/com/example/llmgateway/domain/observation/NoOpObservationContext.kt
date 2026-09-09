package com.example.llmgateway.domain.observation

object NoOpObservationContext : ObservationContext {
    override fun openScope() = AutoCloseable {}
}
