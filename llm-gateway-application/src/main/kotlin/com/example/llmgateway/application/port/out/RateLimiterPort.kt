package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.policy.RateLimitDecision

fun interface RateLimiterPort {
    fun check(context: RequestContext, request: CanonicalChatRequest): RateLimitDecision
}
