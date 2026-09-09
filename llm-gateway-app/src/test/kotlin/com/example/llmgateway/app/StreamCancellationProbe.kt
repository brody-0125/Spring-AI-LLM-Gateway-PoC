package com.example.llmgateway.app

import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.stream.CloseableStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class StreamCancellationProbe {
    val closes = AtomicInteger()
    val frames = AtomicInteger()
    val closed = CountDownLatch(1)
    val stream = AtomicReference<CloseableStream<GatewayEvent>?>()
}
