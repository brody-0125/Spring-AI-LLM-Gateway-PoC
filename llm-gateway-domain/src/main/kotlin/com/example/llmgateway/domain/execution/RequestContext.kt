package com.example.llmgateway.domain.execution

import com.example.llmgateway.core.primitive.ExecutionId
import com.example.llmgateway.core.primitive.RequestId
import java.time.Instant

data class RequestContext(
    val requestId: RequestId,
    val caller: String = "unknown",
    val tenant: String = "default",
    val traceId: String? = null,
    val startedAt: Instant = Instant.now(),
    val deadline: Instant = startedAt.plusSeconds(60),
    val executionId: ExecutionId = ExecutionId.newId(),
)
