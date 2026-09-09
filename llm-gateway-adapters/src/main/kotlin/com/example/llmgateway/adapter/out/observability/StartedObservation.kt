package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.domain.observation.NoOpObservationContext
import com.example.llmgateway.domain.observation.ObservationHandle
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.atomic.AtomicBoolean

/** Per-execution ownership; there is no registry keyed by caller or execution identifiers. */
internal class StartedObservation<O>(
    private val observation: Observation,
    registry: ObservationRegistry,
    private val onFailure: (Exception) -> Unit,
    private val onStop: (O, Long) -> Unit,
) : ObservationHandle<O> {
    private val stopped = AtomicBoolean()
    private val startedNanos = System.nanoTime()
    private val context = MicrometerObservationContext(registry, observation)

    override fun openScope(): AutoCloseable =
        if (stopped.get()) NoOpObservationContext.openScope() else context.openScope()

    @Synchronized
    fun ifActive(action: () -> Unit) {
        if (!stopped.get()) action()
    }

    override fun stop(outcome: O) {
        // The lock orders first-token updates only; handlers/loggers run outside it on JDK 21.
        val claimed = synchronized(this) { stopped.compareAndSet(false, true) }
        if (!claimed) return
        try {
            onStop(outcome, startedNanos)
        } catch (error: Exception) {
            onFailure(error)
        } finally {
            try {
                observation.stop()
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }
}
