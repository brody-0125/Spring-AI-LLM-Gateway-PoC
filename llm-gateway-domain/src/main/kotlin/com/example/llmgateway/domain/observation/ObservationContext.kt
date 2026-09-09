package com.example.llmgateway.domain.observation

/** Captured context, not an open thread-local scope. Open and close on the same thread. */
fun interface ObservationContext {
    fun openScope(): AutoCloseable
}
