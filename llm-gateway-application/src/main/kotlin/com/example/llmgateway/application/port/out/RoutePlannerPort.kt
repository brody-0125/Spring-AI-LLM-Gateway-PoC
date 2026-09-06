package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RoutingPlan

interface RoutePlannerPort {
    fun plan(request: CanonicalChatRequest, context: RequestContext): RoutingPlan
}
