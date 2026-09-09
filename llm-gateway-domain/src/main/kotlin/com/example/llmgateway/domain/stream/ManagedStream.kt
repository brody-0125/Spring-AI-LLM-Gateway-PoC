package com.example.llmgateway.domain.stream

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Pull-based local lifetime. Closing a handle is not proof that a remote request has ended. */
class ManagedStream<T>(
    private val source: (ManagedStream<T>) -> Sequence<T>,
) : CloseableStream<T> {
    private val closed = AtomicBoolean()
    private val consumed = AtomicBoolean()
    private val reader = AtomicReference<Thread?>()
    private val resources = mutableListOf<AutoCloseable>()

    fun ensureOpen() {
        if (closed.get()) throw CancellationException("Stream is closed")
    }

    fun <R : AutoCloseable> own(resource: R): R {
        synchronized(resources) {
            if (!closed.get()) {
                resources += resource
                return resource
            }
        }
        resource.close()
        throw CancellationException("Stream is closed")
    }

    override fun iterator(): Iterator<T> {
        check(consumed.compareAndSet(false, true)) { "Stream permits only one iterator" }
        return object : Iterator<T> {
            private val delegate by lazy { source(this@ManagedStream).iterator() }

            override fun hasNext(): Boolean {
                if (closed.get()) return false
                return read {
                    delegate.hasNext().also { available -> if (!available) close() }
                } && !closed.get()
            }

            override fun next(): T = read {
                if (closed.get()) throw NoSuchElementException("Stream is closed")
                delegate.next().also {
                    if (closed.get()) throw CancellationException("Stream was cancelled")
                }
            }
        }
    }

    private fun <R> read(action: () -> R): R {
        val thread = Thread.currentThread()
        check(reader.compareAndSet(null, thread)) { "Concurrent stream reads are not supported" }
        try {
            if (closed.get()) throw CancellationException("Stream is closed")
            return action()
        } catch (error: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        } finally {
            reader.compareAndSet(thread, null)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reader.get()?.takeIf { it !== Thread.currentThread() }?.interrupt()
        val owned = synchronized(resources) { resources.asReversed().toList().also { resources.clear() } }
        var failure: Throwable? = null
        owned.forEach {
            try {
                it.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
