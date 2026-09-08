package com.example.llmgateway.application.policy

import com.example.llmgateway.domain.model.ErrorCategory
import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.GatewayError
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.RequestContext
import java.time.Duration

class GatewayErrorFactory(
    private val failurePolicy: FailurePolicy,
) {
    fun from(
        context: RequestContext,
        failure: FailureClass,
        cause: Throwable,
        retryAfter: Duration? = null,
    ): GatewayException =
        GatewayException(
            error = GatewayError(
                type = failurePolicy.errorType(failure),
                category = failurePolicy.category(failure),
                retryable = failurePolicy.clientRetryable(failure),
                message = failurePolicy.clientMessage(failure),
                requestId = context.requestId,
                retryAfterSeconds = retryAfter?.toMillis()?.let { (it + 999) / 1_000 }
                    ?.takeIf { it > 0 },
            ),
            cause = cause,
        )

    fun routingUnavailable(context: RequestContext, cause: Throwable): GatewayException =
        GatewayException(
            error = GatewayError(
                type = "routing_unavailable",
                category = ErrorCategory.GATEWAY_FAULT,
                retryable = true,
                message = "The gateway could not determine an available deployment",
                requestId = context.requestId,
            ),
            cause = cause,
        )

    fun noDeployment(
        context: RequestContext,
        failure: FailureClass = FailureClass.UNKNOWN,
        message: String = "No deployment is available for model group",
    ): GatewayException =
        GatewayException(
            error = GatewayError(
                type = failurePolicy.errorType(failure),
                category = failurePolicy.category(failure),
                retryable = failurePolicy.clientRetryable(failure),
                message = message,
                requestId = context.requestId,
            ),
        )

    fun rateLimited(context: RequestContext, retryAfterSeconds: Long): GatewayException =
        GatewayException(
            GatewayError(
                type = "gateway_rate_limited",
                category = ErrorCategory.TRANSIENT,
                retryable = true,
                message = "The gateway rate limit was exceeded",
                requestId = context.requestId,
                retryAfterSeconds = retryAfterSeconds,
            ),
        )

    fun guardrailRejected(context: RequestContext, reason: String?): GatewayException =
        GatewayException(
            GatewayError(
                type = "guardrail_rejected",
                category = ErrorCategory.CALLER_FIXABLE,
                retryable = false,
                message = reason ?: "The request was rejected by a gateway guardrail",
                requestId = context.requestId,
            ),
        )
}
