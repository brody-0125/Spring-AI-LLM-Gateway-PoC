package com.example.llmgateway.application.operator

import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.port.out.InputGuardrailPort
import com.example.llmgateway.application.port.out.RateLimiterPort
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest

class DefaultRequestAdmissionOperator(
    private val rateLimiter: RateLimiterPort,
    private val guardrail: InputGuardrailPort,
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

        val decision = try {
            guardrail.inspect(request, context)
        } catch (error: com.example.llmgateway.domain.error.GatewayException) {
            throw error
        } catch (error: Exception) {
            throw errorFactory.guardrailUnavailable(context, error)
        }
        if (!decision.allowed) {
            throw errorFactory.guardrailRejected(context, decision)
        }
    }
}
