package com.example.llmgateway.bootstrap

import com.example.llmgateway.application.port.`in`.ChatCompletionCommandIn
import com.example.llmgateway.application.port.`in`.ChatCompletionQueryIn
import com.example.llmgateway.application.port.`in`.RoutingCommandIn
import com.example.llmgateway.application.port.`in`.RoutingQueryIn
import com.example.llmgateway.application.port.out.AttemptAccountingPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.InputGuardrailPort
import com.example.llmgateway.application.port.out.OutputGuardrailPort
import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.application.port.out.RateLimiterPort
import com.example.llmgateway.application.port.out.RequestAccountingPort
import com.example.llmgateway.application.port.out.RequestObserverPort
import com.example.llmgateway.application.port.out.RoutingControlPlanePort
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.application.operation.CompleteChatOperation
import com.example.llmgateway.application.operation.DefaultCompleteChatOperation
import com.example.llmgateway.application.operation.DefaultStreamChatOperation
import com.example.llmgateway.application.operation.StreamChatOperation
import com.example.llmgateway.application.operator.CompleteAttemptOperator
import com.example.llmgateway.application.operator.CostCalculationOperator
import com.example.llmgateway.application.operator.DefaultCompleteAttemptOperator
import com.example.llmgateway.application.operator.DefaultOutputGuardrailOperator
import com.example.llmgateway.application.operator.DefaultRequestAdmissionOperator
import com.example.llmgateway.application.operator.DefaultStreamAttemptOperator
import com.example.llmgateway.application.operator.OutputGuardrailOperator
import com.example.llmgateway.application.operator.RequestAdmissionOperator
import com.example.llmgateway.application.operator.RequestLifecycleOperator
import com.example.llmgateway.application.operator.StreamAttemptOperator
import com.example.llmgateway.application.operator.VirtualThreadDeadlineOperator
import com.example.llmgateway.application.policy.DefaultFailureClassifier
import com.example.llmgateway.application.policy.FailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.application.policy.AttemptPolicy
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.service.DefaultChatCompletionCommandService
import com.example.llmgateway.application.service.DefaultChatCompletionQueryService
import com.example.llmgateway.application.service.DefaultRoutingCommandService
import com.example.llmgateway.application.service.DefaultRoutingQueryService
import com.example.llmgateway.application.service.WeightedRendezvousRoutePlanner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class GatewayServiceConfiguration {

    @Bean
    fun failureClassifier(): FailureClassifier = DefaultFailureClassifier()

    @Bean
    fun failurePolicy(): FailurePolicy = FailurePolicy()

    @Bean
    fun attemptPolicy(
        failurePolicy: FailurePolicy,
        @Value("\${gateway.resilience.max-total-attempts:3}") maxTotalAttempts: Int,
        @Value("\${gateway.resilience.max-retries-per-deployment:1}") maxRetriesPerDeployment: Int,
        @Value("\${gateway.resilience.max-fallbacks:2}") maxFallbacks: Int,
        @Value("\${gateway.resilience.backoff.initial:100ms}") initialBackoff: Duration,
        @Value("\${gateway.resilience.backoff.multiplier:2.0}") backoffMultiplier: Double,
        @Value("\${gateway.resilience.backoff.max:2s}") maxBackoff: Duration,
        @Value("\${gateway.resilience.per-attempt-timeout:30s}") perAttemptTimeout: Duration,
    ): AttemptPolicy = AttemptPolicy(
        failurePolicy = failurePolicy,
        maxTotalAttempts = maxTotalAttempts,
        maxRetriesPerDeployment = maxRetriesPerDeployment,
        maxFallbacks = maxFallbacks,
        initialBackoff = initialBackoff,
        backoffMultiplier = backoffMultiplier,
        maxBackoff = maxBackoff,
        perAttemptTimeout = perAttemptTimeout,
    )

    @Bean
    fun gatewayErrorFactory(failurePolicy: FailurePolicy): GatewayErrorFactory = GatewayErrorFactory(failurePolicy)

    @Bean
    fun deadlineOperator(): VirtualThreadDeadlineOperator = VirtualThreadDeadlineOperator()

    @Bean
    fun routePlanner(
        deploymentRegistry: DeploymentRegistryPort,
        circuitBreaker: CircuitBreakerPort,
    ): RoutePlannerPort = WeightedRendezvousRoutePlanner(deploymentRegistry, circuitBreaker)

    @Bean
    fun requestAdmissionOperator(
        rateLimiter: RateLimiterPort,
        guardrail: InputGuardrailPort,
        gatewayErrorFactory: GatewayErrorFactory,
    ) = DefaultRequestAdmissionOperator(rateLimiter, guardrail, gatewayErrorFactory)

    @Bean
    fun outputGuardrailOperator(
        outputGuardrailPort: OutputGuardrailPort,
        gatewayErrorFactory: GatewayErrorFactory,
        @Value("\${gateway.guardrails.max-output-characters:100000}") maxOutputCharacters: Int,
        @Value("\${gateway.guardrails.stream-inspection-window-characters:4096}") inspectionWindowCharacters: Int,
    ): OutputGuardrailOperator = DefaultOutputGuardrailOperator(
        guardrail = outputGuardrailPort,
        errorFactory = gatewayErrorFactory,
        maxStreamOutputCharacters = maxOutputCharacters,
        streamInspectionWindowCharacters = inspectionWindowCharacters,
    )

    @Bean
    fun completeAttemptOperator(
        providerInvoker: ProviderInvokerPort,
        failureClassifier: FailureClassifier,
        failurePolicy: FailurePolicy,
        attemptPolicy: AttemptPolicy,
        attemptObserver: AttemptObserverPort,
        deadlineOperator: VirtualThreadDeadlineOperator,
        circuitBreaker: CircuitBreakerPort,
        costCalculationOperator: CostCalculationOperator,
        attemptAccounting: AttemptAccountingPort,
        outputGuardrailOperator: OutputGuardrailOperator,
    ): CompleteAttemptOperator = DefaultCompleteAttemptOperator(
        providerInvoker = providerInvoker,
        failureClassifier = failureClassifier,
        attemptObserver = attemptObserver,
        deadlineOperator = deadlineOperator,
        circuitBreaker = circuitBreaker,
        failurePolicy = failurePolicy,
        attemptPolicy = attemptPolicy,
        costCalculationOperator = costCalculationOperator,
        attemptAccounting = attemptAccounting,
        outputGuardrailOperator = outputGuardrailOperator,
    )

    @Bean
    fun streamAttemptOperator(
        providerInvoker: ProviderInvokerPort,
        failureClassifier: FailureClassifier,
        failurePolicy: FailurePolicy,
        attemptPolicy: AttemptPolicy,
        attemptObserver: AttemptObserverPort,
        deadlineOperator: VirtualThreadDeadlineOperator,
        circuitBreaker: CircuitBreakerPort,
        costCalculationOperator: CostCalculationOperator,
        attemptAccounting: AttemptAccountingPort,
        outputGuardrailOperator: OutputGuardrailOperator,
    ): StreamAttemptOperator = DefaultStreamAttemptOperator(
        providerInvoker = providerInvoker,
        failureClassifier = failureClassifier,
        attemptObserver = attemptObserver,
        deadlineOperator = deadlineOperator,
        circuitBreaker = circuitBreaker,
        failurePolicy = failurePolicy,
        attemptPolicy = attemptPolicy,
        costCalculationOperator = costCalculationOperator,
        attemptAccounting = attemptAccounting,
        outputGuardrailOperator = outputGuardrailOperator,
    )

    @Bean
    fun completeChatOperation(
        routePlanner: RoutePlannerPort,
        attemptOperator: CompleteAttemptOperator,
        attemptPolicy: AttemptPolicy,
        gatewayErrorFactory: GatewayErrorFactory,
    ): CompleteChatOperation = DefaultCompleteChatOperation(
        routePlanner = routePlanner,
        attemptOperator = attemptOperator,
        attemptPolicy = attemptPolicy,
        errorFactory = gatewayErrorFactory,
    )

    @Bean
    fun streamChatOperation(
        routePlanner: RoutePlannerPort,
        attemptOperator: StreamAttemptOperator,
        attemptPolicy: AttemptPolicy,
        gatewayErrorFactory: GatewayErrorFactory,
    ): StreamChatOperation = DefaultStreamChatOperation(
        routePlanner = routePlanner,
        attemptOperator = attemptOperator,
        attemptPolicy = attemptPolicy,
        errorFactory = gatewayErrorFactory,
    )

    @Bean
    fun requestLifecycleOperator(
        requestAccounting: RequestAccountingPort,
        requestObserver: RequestObserverPort,
    ): RequestLifecycleOperator = RequestLifecycleOperator(requestObserver, requestAccounting)

    @Bean
    fun chatCompletionQueryIn(
        completeOperation: CompleteChatOperation,
        requestAdmissionOperator: RequestAdmissionOperator,
        requestLifecycleOperator: RequestLifecycleOperator,
    ): ChatCompletionQueryIn = DefaultChatCompletionQueryService(
        completeOperation,
        requestAdmissionOperator,
        requestLifecycleOperator,
    )

    @Bean
    fun chatCompletionCommandIn(
        streamOperation: StreamChatOperation,
        requestAdmissionOperator: RequestAdmissionOperator,
        requestLifecycleOperator: RequestLifecycleOperator,
    ): ChatCompletionCommandIn = DefaultChatCompletionCommandService(
        streamOperation,
        requestAdmissionOperator,
        requestLifecycleOperator,
    )

    @Bean
    fun routingCommandIn(controlPlane: RoutingControlPlanePort): RoutingCommandIn =
        DefaultRoutingCommandService(controlPlane)

    @Bean
    fun routingQueryIn(deploymentRegistry: DeploymentRegistryPort): RoutingQueryIn =
        DefaultRoutingQueryService(deploymentRegistry)
}
