package com.example.llmgateway.bootstrap

import com.example.llmgateway.application.port.`in`.ChatCompletionCommandIn
import com.example.llmgateway.application.port.`in`.ChatCompletionQueryIn
import com.example.llmgateway.application.port.`in`.RoutingCommandIn
import com.example.llmgateway.application.port.`in`.RoutingQueryIn
import com.example.llmgateway.application.port.out.AttemptAccountingPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.GuardrailPort
import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.application.port.out.RateLimiterPort
import com.example.llmgateway.application.port.out.RoutingControlPlanePort
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.application.operation.CompleteChatOperation
import com.example.llmgateway.application.operation.DefaultCompleteChatOperation
import com.example.llmgateway.application.operation.DefaultStreamChatOperation
import com.example.llmgateway.application.operation.StreamChatOperation
import com.example.llmgateway.application.operator.CompleteAttemptOperator
import com.example.llmgateway.application.operator.CostCalculationOperator
import com.example.llmgateway.application.operator.DefaultCompleteAttemptOperator
import com.example.llmgateway.application.operator.DefaultRequestAdmissionOperator
import com.example.llmgateway.application.operator.DefaultStreamAttemptOperator
import com.example.llmgateway.application.operator.RequestAdmissionOperator
import com.example.llmgateway.application.operator.StreamAttemptOperator
import com.example.llmgateway.application.operator.VirtualThreadDeadlineOperator
import com.example.llmgateway.application.policy.DefaultFailureClassifier
import com.example.llmgateway.application.policy.FailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.application.policy.FallbackPolicy
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.service.DefaultChatCompletionCommandService
import com.example.llmgateway.application.service.DefaultChatCompletionQueryService
import com.example.llmgateway.application.service.DefaultRoutingCommandService
import com.example.llmgateway.application.service.DefaultRoutingQueryService
import com.example.llmgateway.application.service.WeightedRoundRobinRoutePlanner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class GatewayServiceConfiguration {

    @Bean
    fun failureClassifier(): FailureClassifier = DefaultFailureClassifier()

    @Bean
    fun failurePolicy(): FailurePolicy = FailurePolicy()

    @Bean
    fun fallbackPolicy(failurePolicy: FailurePolicy): FallbackPolicy = FallbackPolicy(failurePolicy)

    @Bean
    fun gatewayErrorFactory(failurePolicy: FailurePolicy): GatewayErrorFactory = GatewayErrorFactory(failurePolicy)

    @Bean
    fun deadlineOperator(): VirtualThreadDeadlineOperator = VirtualThreadDeadlineOperator()

    @Bean
    fun routePlanner(
        deploymentRegistry: DeploymentRegistryPort,
        circuitBreaker: CircuitBreakerPort,
    ): RoutePlannerPort = WeightedRoundRobinRoutePlanner(deploymentRegistry, circuitBreaker)

    @Bean
    fun requestAdmissionOperator(
        rateLimiter: RateLimiterPort,
        guardrail: GuardrailPort,
        gatewayErrorFactory: GatewayErrorFactory,
    ) = DefaultRequestAdmissionOperator(rateLimiter, guardrail, gatewayErrorFactory)

    @Bean
    fun completeAttemptOperator(
        providerInvoker: ProviderInvokerPort,
        failureClassifier: FailureClassifier,
        failurePolicy: FailurePolicy,
        attemptObserver: AttemptObserverPort,
        deadlineOperator: VirtualThreadDeadlineOperator,
        circuitBreaker: CircuitBreakerPort,
        costCalculationOperator: CostCalculationOperator,
        attemptAccounting: AttemptAccountingPort,
    ): CompleteAttemptOperator = DefaultCompleteAttemptOperator(
        providerInvoker = providerInvoker,
        failureClassifier = failureClassifier,
        attemptObserver = attemptObserver,
        deadlineOperator = deadlineOperator,
        circuitBreaker = circuitBreaker,
        failurePolicy = failurePolicy,
        costCalculationOperator = costCalculationOperator,
        attemptAccounting = attemptAccounting,
    )

    @Bean
    fun streamAttemptOperator(
        providerInvoker: ProviderInvokerPort,
        failureClassifier: FailureClassifier,
        failurePolicy: FailurePolicy,
        attemptObserver: AttemptObserverPort,
        deadlineOperator: VirtualThreadDeadlineOperator,
        circuitBreaker: CircuitBreakerPort,
        costCalculationOperator: CostCalculationOperator,
        attemptAccounting: AttemptAccountingPort,
    ): StreamAttemptOperator = DefaultStreamAttemptOperator(
        providerInvoker = providerInvoker,
        failureClassifier = failureClassifier,
        attemptObserver = attemptObserver,
        deadlineOperator = deadlineOperator,
        circuitBreaker = circuitBreaker,
        failurePolicy = failurePolicy,
        costCalculationOperator = costCalculationOperator,
        attemptAccounting = attemptAccounting,
    )

    @Bean
    fun completeChatOperation(
        routePlanner: RoutePlannerPort,
        attemptOperator: CompleteAttemptOperator,
        fallbackPolicy: FallbackPolicy,
        gatewayErrorFactory: GatewayErrorFactory,
    ): CompleteChatOperation = DefaultCompleteChatOperation(
        routePlanner = routePlanner,
        attemptOperator = attemptOperator,
        fallbackPolicy = fallbackPolicy,
        errorFactory = gatewayErrorFactory,
    )

    @Bean
    fun streamChatOperation(
        routePlanner: RoutePlannerPort,
        attemptOperator: StreamAttemptOperator,
        fallbackPolicy: FallbackPolicy,
        gatewayErrorFactory: GatewayErrorFactory,
    ): StreamChatOperation = DefaultStreamChatOperation(
        routePlanner = routePlanner,
        attemptOperator = attemptOperator,
        fallbackPolicy = fallbackPolicy,
        errorFactory = gatewayErrorFactory,
    )

    @Bean
    fun chatCompletionQueryIn(
        completeOperation: CompleteChatOperation,
        requestAdmissionOperator: RequestAdmissionOperator,
    ): ChatCompletionQueryIn = DefaultChatCompletionQueryService(completeOperation, requestAdmissionOperator)

    @Bean
    fun chatCompletionCommandIn(
        streamOperation: StreamChatOperation,
        requestAdmissionOperator: RequestAdmissionOperator,
    ): ChatCompletionCommandIn = DefaultChatCompletionCommandService(streamOperation, requestAdmissionOperator)

    @Bean
    fun routingCommandIn(controlPlane: RoutingControlPlanePort): RoutingCommandIn =
        DefaultRoutingCommandService(controlPlane)

    @Bean
    fun routingQueryIn(deploymentRegistry: DeploymentRegistryPort): RoutingQueryIn =
        DefaultRoutingQueryService(deploymentRegistry)
}
