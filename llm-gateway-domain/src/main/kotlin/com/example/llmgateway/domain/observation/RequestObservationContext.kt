package com.example.llmgateway.domain.observation

import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.domain.execution.RequestContext

/** Metadata-only boundary: observers never receive messages, documents or tool payloads. */
data class RequestObservationContext(
    val requestContext: RequestContext,
    val modelGroup: ModelGroup,
    val streaming: Boolean,
)
