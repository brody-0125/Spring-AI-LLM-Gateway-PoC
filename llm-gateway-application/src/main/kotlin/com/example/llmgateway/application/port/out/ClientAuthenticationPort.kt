package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.identity.GatewayPrincipal

fun interface ClientAuthenticationPort {
    fun authenticate(authorizationHeader: String?): GatewayPrincipal?
}
