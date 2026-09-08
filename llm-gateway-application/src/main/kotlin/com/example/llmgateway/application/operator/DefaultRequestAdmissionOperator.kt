package com.example.llmgateway.application.operator

import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.port.out.GuardrailPort
import com.example.llmgateway.application.port.out.RateLimiterPort
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.RequestContext

class DefaultRequestAdmissionOperator(
    private val rateLimiter: RateLimiterPort,
    private val guardrail: GuardrailPort,
    private val errorFactory: GatewayErrorFactory,
) : RequestAdmissionOperator {
    override fun execute(request: CanonicalChatRequest, context: RequestContext) {
        val rateLimit = try {
            rateLimiter.check(context, request)
        } catch (error: Exception) {
            throw errorFactory.rateLimitUnavailable(context, error)
        }
        if (!rateLimit.backendAvailable) {
            throw errorFactory.rateLimitUnavailable(context, null)
        }
        if (!rateLimit.allowed) {
            throw errorFactory.rateLimited(context, rateLimit.retryAfterSeconds)
        }

        val decision = guardrail.inspect(request, context)
        if (!decision.allowed) {
            throw errorFactory.guardrailRejected(context, decision.reason)
        }
    }
}
