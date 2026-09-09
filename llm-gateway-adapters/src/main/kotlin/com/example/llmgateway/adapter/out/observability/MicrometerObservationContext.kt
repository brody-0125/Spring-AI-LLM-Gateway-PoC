package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.domain.observation.ObservationContext
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry

/** Restores the registry even if an observation handler throws during scope callbacks. */
internal class MicrometerObservationContext(
    private val registry: ObservationRegistry,
    private val observation: Observation?,
) : ObservationContext {
    override fun openScope(): AutoCloseable {
        val previous = registry.currentObservationScope
        val owner = Thread.currentThread()
        val scope = try {
            if (observation == null) {
                registry.setCurrentObservationScope(null)
                null
            } else {
                observation.openScope()
            }
        } catch (error: Exception) {
            registry.setCurrentObservationScope(previous)
            throw error
        }
        return AutoCloseable {
            check(Thread.currentThread() === owner) { "Observation scope must close on its opening thread" }
            try {
                scope?.close()
            } finally {
                registry.setCurrentObservationScope(previous)
            }
        }
    }
}
