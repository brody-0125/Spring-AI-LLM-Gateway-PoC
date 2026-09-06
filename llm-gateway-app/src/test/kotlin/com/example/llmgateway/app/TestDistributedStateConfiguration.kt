package com.example.llmgateway.app

import com.example.llmgateway.adapter.out.springai.ConfiguredProviders
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.RateLimiterPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment

@TestConfiguration(proxyBeanMethods = false)
class TestDistributedStateConfiguration {

    @Bean
    @Primary
    fun testDeploymentRegistry(providers: ConfiguredProviders): TestDeploymentRegistryAdapter =
        TestDeploymentRegistryAdapter(providers.deployments)

    @Bean
    @Primary
    fun testRateLimiter(environment: Environment): RateLimiterPort = TestTokenBucketRateLimiter(
        enabled = environment.getProperty("gateway.rate-limit.enabled", Boolean::class.java) ?: false,
        requestsPerMinute = environment.getProperty("gateway.rate-limit.requests-per-minute", Int::class.java) ?: 120,
        burst = environment.getProperty("gateway.rate-limit.burst", Int::class.java) ?: 20,
    )

    @Bean
    @Primary
    fun testCircuitBreaker(): CircuitBreakerPort = TestCircuitBreakerAdapter()
}
