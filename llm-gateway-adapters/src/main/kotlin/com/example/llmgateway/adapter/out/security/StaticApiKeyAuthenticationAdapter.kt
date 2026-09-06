package com.example.llmgateway.adapter.out.security

import com.example.llmgateway.application.port.out.ClientAuthenticationPort
import com.example.llmgateway.domain.model.GatewayPrincipal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class StaticApiKeyAuthenticationAdapter(
    private val enabled: Boolean,
    clients: Map<String, GatewayPrincipal>,
) : ClientAuthenticationPort {

    private val clients = clients.filterKeys(String::isNotBlank)

    override fun authenticate(authorizationHeader: String?): GatewayPrincipal? {
        if (!enabled) return GatewayPrincipal(caller = "anonymous", tenant = "default")
        val token = authorizationHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ', "")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null

        return clients.entries.firstOrNull { (configured, _) ->
            MessageDigest.isEqual(
                configured.toByteArray(StandardCharsets.UTF_8),
                token.toByteArray(StandardCharsets.UTF_8),
            )
        }?.value
    }
}
