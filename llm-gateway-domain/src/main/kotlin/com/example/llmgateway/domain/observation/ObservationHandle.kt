package com.example.llmgateway.domain.observation

/** Owned by one execution. stop must be thread-safe and idempotent. */
interface ObservationHandle<in O> : ObservationContext {
    override fun openScope(): AutoCloseable = NoOpObservationContext.openScope()
    fun stop(outcome: O)
}
