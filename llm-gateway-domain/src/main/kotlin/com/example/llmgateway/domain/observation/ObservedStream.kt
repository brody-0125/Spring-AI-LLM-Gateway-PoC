package com.example.llmgateway.domain.observation

import com.example.llmgateway.domain.stream.CloseableStream

/** Each pull and cleanup owns its scope; no ThreadLocal survives a suspended sequence. */
class ObservedStream<T>(
    private val delegate: CloseableStream<T>,
    private val context: ObservationContext,
) : CloseableStream<T> {
    override fun iterator(): Iterator<T> {
        val iterator = context.withObservationScope { delegate.iterator() }
        return object : Iterator<T> {
            override fun hasNext(): Boolean = context.withObservationScope { iterator.hasNext() }
            override fun next(): T = context.withObservationScope { iterator.next() }
        }
    }

    override fun close() = context.withObservationScope { delegate.close() }
}
