package com.example.llmgateway.domain.observation

/** Telemetry failures cannot replace the business result or leave a scope open over a yield. */
inline fun <T> ObservationContext.withObservationScope(action: () -> T): T {
    val scope = try {
        openScope()
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        null
    }
    try {
        return action()
    } finally {
        try {
            scope?.close()
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
        }
    }
}
