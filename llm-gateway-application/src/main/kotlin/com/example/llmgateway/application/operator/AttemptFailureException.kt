package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.ProviderException
import com.example.llmgateway.domain.model.ProviderFailurePhase
import com.example.llmgateway.domain.model.RequestDisposition
import java.time.Duration

class AttemptFailureException(
    val failureClass: FailureClass,
    val emitted: Boolean = false,
    val phase: ProviderFailurePhase = ProviderFailurePhase.UNKNOWN,
    val requestDisposition: RequestDisposition = RequestDisposition.SENT_UNKNOWN,
    val retryAfter: Duration? = null,
    val providerRequestId: String? = null,
    cause: Throwable,
) : RuntimeException(cause.message, cause) {
    companion object {
        fun from(
            failureClass: FailureClass,
            error: Throwable,
            emitted: Boolean = false,
            providerRequestId: String? = null,
        ): AttemptFailureException {
            val provider = error as? ProviderException
            return AttemptFailureException(
                failureClass = failureClass,
                emitted = emitted,
                phase = provider?.phase ?: ProviderFailurePhase.UNKNOWN,
                requestDisposition = provider?.requestDisposition ?: RequestDisposition.SENT_UNKNOWN,
                retryAfter = provider?.retryAfter,
                providerRequestId = provider?.providerRequestId ?: providerRequestId,
                cause = error,
            )
        }
    }
}
