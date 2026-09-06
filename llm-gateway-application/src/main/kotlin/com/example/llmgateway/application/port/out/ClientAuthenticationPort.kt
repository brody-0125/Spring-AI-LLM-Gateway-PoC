package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.GatewayPrincipal

fun interface ClientAuthenticationPort {
    fun authenticate(authorizationHeader: String?): GatewayPrincipal?
}
