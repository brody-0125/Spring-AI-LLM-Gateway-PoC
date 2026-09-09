package com.example.llmgateway.domain.error

import com.example.llmgateway.core.primitive.Vendor
import java.time.Duration


class ProviderException(
    val vendor: Vendor,
    val statusCode: Int? = null,
    val providerCode: String? = null,
    message: String,
    cause: Throwable? = null,
    val phase: ProviderFailurePhase = if (statusCode == null) {
        ProviderFailurePhase.UNKNOWN
    } else {
        ProviderFailurePhase.COMPLETE
    },
    val requestDisposition: RequestDisposition = RequestDisposition.SENT_UNKNOWN,
    val retryAfter: Duration? = null,
    val providerRequestId: String? = null,
) : RuntimeException(message, cause)
