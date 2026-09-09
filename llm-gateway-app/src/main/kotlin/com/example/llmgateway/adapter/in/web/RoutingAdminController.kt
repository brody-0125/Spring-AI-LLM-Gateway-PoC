package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.adapter.`in`.web.internal.DeploymentOverrideDto
import com.example.llmgateway.adapter.`in`.web.internal.RoutingSnapshotDto
import com.example.llmgateway.adapter.`in`.web.internal.RoutingUpdateRequest
import com.example.llmgateway.application.port.`in`.RoutingCommandIn
import com.example.llmgateway.application.port.`in`.RoutingQueryIn
import com.example.llmgateway.application.port.`in`.RoutingUpdateCommand
import com.example.llmgateway.application.port.out.ClientAuthenticationPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/v1/routing")
class RoutingAdminController(
    private val routingCommandIn: RoutingCommandIn,
    private val routingQueryIn: RoutingQueryIn,
    private val clientAuthenticationPort: ClientAuthenticationPort,
) {

    @GetMapping
    fun snapshot(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader("X-Request-Id", required = false) requestHeader: String?,
        response: HttpServletResponse,
    ): RoutingSnapshotDto {
        val requestId = requestId(requestHeader)
        response.setHeader("X-Request-Id", requestId.value)
        authenticateAdministrator(authorization, requestId)
        return RoutingSnapshotMapper.toContract(
            routingQueryIn.snapshot(),
        )
    }

    @PutMapping
    fun update(
        @RequestBody request: RoutingUpdateRequest,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader("X-Request-Id", required = false) requestHeader: String?,
        response: HttpServletResponse,
    ): RoutingSnapshotDto {
        val requestId = requestId(requestHeader)
        response.setHeader("X-Request-Id", requestId.value)
        authenticateAdministrator(authorization, requestId)
        return RoutingSnapshotMapper.toContract(
            routingCommandIn.update(
                RoutingUpdateCommand(request.overrides.map(DeploymentOverrideDto::toDomain)),
            ),
        )
    }

    private fun authenticateAdministrator(
        authorization: String?,
        requestId: RequestId,
    ) {
        val principal = clientAuthenticationPort.authenticate(authorization)
            ?: throw GatewayException(
                GatewayError(
                    type = "authentication_required",
                    code = "CLIENT_UNAUTHORIZED",
                    category = ErrorCategory.ENTITLEMENT,
                    retryable = false,
                    message = "A valid gateway bearer token is required",
                    requestId = requestId,
                ),
            )
        if (!principal.administrator) {
            throw GatewayException(
                GatewayError(
                    type = "administrator_required",
                    code = "ADMINISTRATOR_REQUIRED",
                    category = ErrorCategory.ENTITLEMENT,
                    retryable = false,
                    message = "Administrator privileges are required",
                    requestId = requestId,
                ),
            )
        }
    }

    private fun requestId(value: String?): RequestId =
        RequestId(value?.takeIf(String::isNotBlank) ?: "req_${UUID.randomUUID()}")
}

private fun DeploymentOverrideDto.toDomain() = com.example.llmgateway.domain.policy.DeploymentOverride(
    id = DeploymentId(id),
    enabled = enabled,
    priority = priority,
    weight = weight,
)
