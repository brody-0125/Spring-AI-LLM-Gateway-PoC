package com.example.llmgateway.domain.stream

/** Single-consumer stream. Owners must close it on early termination, preferably with use. */
interface CloseableStream<out T> : Sequence<T>, AutoCloseable {
    fun cancel() = close()
}
