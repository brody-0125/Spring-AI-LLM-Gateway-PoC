package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.RateLimitDecision
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.RequestContext

fun interface RateLimiterPort {
    fun check(context: RequestContext, request: CanonicalChatRequest): RateLimitDecision
}
