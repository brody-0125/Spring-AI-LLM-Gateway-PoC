package com.example.llmgateway.domain.model
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.core.primitive.RequestId


import java.time.Instant

data class AttemptContext(
    val requestId: RequestId,
    val attemptId: AttemptId,
    val sequence: Int,
    val deployment: Deployment,
    val caller: String = "unknown",
    val tenant: String = "default",
    val traceId: String? = null,
    val streaming: Boolean = false,
    val startedAt: Instant = Instant.now(),
    val deadline: Instant = Instant.now().plusSeconds(60),
)
