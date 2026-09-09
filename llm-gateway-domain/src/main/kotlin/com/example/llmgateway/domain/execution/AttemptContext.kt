package com.example.llmgateway.domain.execution

import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.core.primitive.ExecutionId
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.routing.Deployment
import java.time.Instant

data class AttemptContext(
    val requestId: RequestId,
    val attemptId: AttemptId,
    val sequence: Int,
    val deployment: Deployment,
    val executionId: ExecutionId,
    val kind: AttemptKind,
    val caller: String = "unknown",
    val tenant: String = "default",
    val traceId: String? = null,
    val streaming: Boolean = false,
    val startedAt: Instant = Instant.now(),
    val deadline: Instant = Instant.now().plusSeconds(60),
    val budgetAt: Instant = startedAt,
    val requestedOutputTokens: Int? = null,
    val requestDeadline: Instant = deadline,
)
