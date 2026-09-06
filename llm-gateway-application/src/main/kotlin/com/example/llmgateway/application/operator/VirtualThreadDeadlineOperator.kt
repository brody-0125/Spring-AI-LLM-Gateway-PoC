package com.example.llmgateway.application.operator

import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class VirtualThreadDeadlineOperator {
    fun <T> execute(deadline: Instant, operation: () -> T): T {
        val remaining = Duration.between(Instant.now(), deadline)
        if (remaining.isZero || remaining.isNegative) {
            throw TimeoutException("Gateway deadline exceeded")
        }

        val task = FutureTask(Callable { operation() })
        Thread.ofVirtual().name("llm-gateway-provider-call").start(task)
        return try {
            task.get(remaining.toNanos(), TimeUnit.NANOSECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: TimeoutException) {
            task.cancel(true)
            throw error
        } catch (error: InterruptedException) {
            task.cancel(true)
            throw error
        } finally {
            if (!task.isDone) task.cancel(true)
        }
    }
}
