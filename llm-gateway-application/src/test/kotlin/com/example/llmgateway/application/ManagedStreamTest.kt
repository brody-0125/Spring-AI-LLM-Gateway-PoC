package com.example.llmgateway.application

import com.example.llmgateway.domain.stream.ManagedStream
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ManagedStreamTest : FunSpec({
    test("EOF, early close, and source failure release every owned resource once") {
        for (mode in listOf("eof", "early", "failure")) {
            val closes = AtomicInteger()
            val stream = ManagedStream { scope -> sequence {
                scope.own(AutoCloseable { closes.incrementAndGet() })
                yield(1)
                if (mode == "failure") error("source failure")
                yield(2)
            } }
            if (mode == "failure") shouldThrow<IllegalStateException> { stream.toList() }
            else stream.use { if (mode == "early") it.take(1).toList() else it.toList() }
            stream.close()
            stream.cancel()
            closes.get() shouldBe 1
        }
    }

    test("close before consumption does not start the source and rejects late resources") {
        var opened = 0
        var closed = 0
        val stream = ManagedStream<Int> { opened++; sequenceOf(1) }
        stream.close()
        stream.toList() shouldBe emptyList()
        opened shouldBe 0
        shouldThrow<CancellationException> { stream.own(AutoCloseable { closed++ }) }
        closed shouldBe 1
        shouldThrow<IllegalStateException> { stream.iterator() }
    }

    test("concurrent cancel interrupts a blocked pull and closes its resource once") {
        val entered = CountDownLatch(1)
        val closes = AtomicInteger()
        val stream = ManagedStream { scope -> sequence {
            scope.own(AutoCloseable { closes.incrementAndGet() })
            entered.countDown()
            CountDownLatch(1).await()
            yield(1)
        } }
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val reading = executor.submit<Boolean> { runCatching { stream.iterator().hasNext() }.isFailure }
            check(entered.await(5, TimeUnit.SECONDS))
            stream.cancel()
            reading.get(5, TimeUnit.SECONDS) shouldBe true
        }
        closes.get() shouldBe 1
    }

    test("resources produced after cancellation are closed even when creation ignores interruption") {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val stream = ManagedStream { scope -> sequence {
            entered.countDown()
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { /* emulate an uninterruptible client */ }
            }
            scope.own(AutoCloseable { closes.incrementAndGet() })
            yield(1)
        } }
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val reading = executor.submit { runCatching { stream.toList() } }
            try {
                check(entered.await(5, TimeUnit.SECONDS))
                stream.close()
            } finally {
                release.countDown()
            }
            reading.get(5, TimeUnit.SECONDS)
        }
        closes.get() shouldBe 1
    }

    test("cleanup failure preserves the source failure and still closes sibling resources") {
        var closes = 0
        val stream = ManagedStream<Int> { scope -> sequence {
            scope.own(AutoCloseable { closes++ })
            scope.own(AutoCloseable { error("cleanup failure") })
            error("source failure")
        } }
        val error = shouldThrow<IllegalStateException> { stream.toList() }
        error.message shouldBe "source failure"
        error.suppressed.single().message shouldBe "cleanup failure"
        closes shouldBe 1
    }
})
