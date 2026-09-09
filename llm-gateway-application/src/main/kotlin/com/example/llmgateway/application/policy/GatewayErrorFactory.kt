package com.example.llmgateway.application.policy

import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.FailureClass
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.policy.GuardrailDecision
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
                code = failurePolicy.errorCode(failure),
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
                code = "ROUTING_UNAVAILABLE",
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
                code = failurePolicy.errorCode(failure),
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
                code = "RATE_LIMITED",
                category = ErrorCategory.TRANSIENT,
                retryable = true,
                message = "The gateway rate limit was exceeded",
                requestId = context.requestId,
                retryAfterSeconds = retryAfterSeconds,
            ),
        )

    fun rateLimitUnavailable(context: RequestContext, cause: Throwable?): GatewayException =
        GatewayException(
            GatewayError(
                type = "rate_limit_unavailable",
                code = "RATE_LIMIT_BACKEND_UNAVAILABLE",
                category = ErrorCategory.TRANSIENT,
                retryable = true,
                message = "The gateway rate-limit service is temporarily unavailable",
                requestId = context.requestId,
                retryAfterSeconds = 1,
            ),
            cause,
        )

    fun guardrailRejected(
        context: RequestContext,
        decision: GuardrailDecision,
        output: Boolean = false,
    ): GatewayException =
        GatewayException(
            GatewayError(
                type = if (output) "response_guardrail_rejected" else "guardrail_rejected",
                code = decision.publicCode(output),
                category = ErrorCategory.CALLER_FIXABLE,
                retryable = false,
                message = if (output) {
                    "The model response was rejected by a gateway policy"
                } else {
                    "The request was rejected by a gateway policy"
                },
                requestId = context.requestId,
            ),
        )

    fun guardrailUnavailable(context: RequestContext, cause: Throwable): GatewayException =
        GatewayException(
            GatewayError(
                type = "guardrail_unavailable",
                code = "GUARDRAIL_UNAVAILABLE",
                category = ErrorCategory.TRANSIENT,
                retryable = true,
                message = "The gateway policy service is temporarily unavailable",
                requestId = context.requestId,
                retryAfterSeconds = 1,
            ),
            cause,
        )

    private fun GuardrailDecision.publicCode(output: Boolean): String = when {
        output && code == "OUTPUT_TOO_LARGE" -> code
        !output && code == "INPUT_TOO_LARGE" -> code
        output -> "OUTPUT_POLICY_BLOCKED"
        else -> "INPUT_POLICY_BLOCKED"
    }

    fun gatewayTimeout(context: RequestContext, cause: Throwable): GatewayException =
        from(context, FailureClass.GATEWAY_TIMEOUT, cause)
}
