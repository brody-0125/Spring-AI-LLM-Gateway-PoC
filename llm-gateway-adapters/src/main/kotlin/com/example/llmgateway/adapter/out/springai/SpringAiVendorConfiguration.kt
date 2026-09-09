package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.adapter.out.admission.ConfigurableInputGuardrailAdapter
import com.example.llmgateway.adapter.out.admission.ConfigurableOutputGuardrailAdapter
import com.example.llmgateway.adapter.out.observability.MicrometerAttemptObserver
import com.example.llmgateway.adapter.out.observability.MicrometerObservationContextAdapter
import com.example.llmgateway.adapter.out.observability.MicrometerRequestObserver
import com.example.llmgateway.adapter.out.postgres.PostgresAttemptJournalAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresDeploymentRegistryAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresPricingCatalogAdapter
import com.example.llmgateway.adapter.out.postgres.PostgresExecutionJournalAdapter
import com.example.llmgateway.adapter.out.pricing.ConfiguredPricingCatalogAdapter
import com.example.llmgateway.adapter.out.redis.RedisCircuitBreakerAdapter
import com.example.llmgateway.adapter.out.redis.RedisTokenBucketRateLimiter
import com.example.llmgateway.adapter.out.security.StaticApiKeyAuthenticationAdapter
import com.example.llmgateway.application.operator.CostCalculationOperator
import com.example.llmgateway.application.operator.DefaultCostCalculationOperator
import com.example.llmgateway.application.port.out.AttemptJournalPort
import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.ClientAuthenticationPort
import com.example.llmgateway.application.port.out.InputGuardrailPort
import com.example.llmgateway.application.port.out.NoOpRequestAccountingPort
import com.example.llmgateway.application.port.out.ObservationContextPort
import com.example.llmgateway.application.port.out.OutputGuardrailPort
import com.example.llmgateway.application.port.out.PricingCatalogPort
import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.application.port.out.RateLimiterPort
import com.example.llmgateway.application.port.out.RequestAccountingPort
import com.example.llmgateway.application.port.out.RequestObserverPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.identity.GatewayPrincipal
import com.example.llmgateway.domain.routing.Deployment
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.bedrock.converse.BedrockChatOptions
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import software.amazon.awssdk.regions.Region

@Configuration(proxyBeanMethods = false)
class SpringAiVendorConfiguration {

    @Bean
    fun observationContextPort(registry: ObservationRegistry): ObservationContextPort =
        MicrometerObservationContextAdapter(registry)

    @Bean
    @ConfigurationProperties("gateway.providers")
    fun gatewayProviderProperties(): GatewayProviderProperties = GatewayProviderProperties()

    @Bean
    fun configuredProviders(
        properties: GatewayProviderProperties,
        observationRegistry: ObservationRegistry,
        environment: Environment,
    ): ConfiguredProviders {
        val deployments = mutableListOf<Deployment>()
        val models = mutableMapOf<DeploymentId, ChatModel>()
        val buildClients = !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("registry-seed"))

        addOpenAiCompatible(
            properties = properties.openai,
            observationRegistry = observationRegistry,
            deployments = deployments,
            models = models,
            vendor = Vendor.OPENAI,
            dialect = Dialect.OPENAI,
            defaultId = "openai-default",
            defaultModel = "gpt-4o-mini",
            defaultBaseUrl = "https://api.openai.com/v1",
            buildClients = buildClients,
        )
        addOpenAiCompatible(
            properties = properties.openrouter,
            observationRegistry = observationRegistry,
            deployments = deployments,
            models = models,
            vendor = Vendor.OPENROUTER,
            dialect = Dialect.OPENAI_COMPATIBLE_OPENROUTER,
            defaultId = "openrouter-default",
            defaultModel = "openai/gpt-4o-mini",
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            buildClients = buildClients,
        )
        addBedrock(properties.bedrock, observationRegistry, deployments, models, buildClients)

        return ConfiguredProviders(deployments, models)
    }

    @Bean
    @Profile("!test")
    @DependsOn("flywayInitializer")
    fun deploymentRegistry(
        jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ): PostgresDeploymentRegistryAdapter = PostgresDeploymentRegistryAdapter(
        jdbcTemplate = jdbcTemplate,
        transactionManager = transactionManager,
    )

    @Bean
    @Profile("!test")
    @DependsOn("flywayInitializer")
    fun pricingCatalog(
        jdbcTemplate: JdbcTemplate,
    ): PostgresPricingCatalogAdapter = PostgresPricingCatalogAdapter(
        jdbcTemplate = jdbcTemplate,
    )

    @Bean
    @Profile("test")
    fun pricingCatalogForTest(): PricingCatalogPort = ConfiguredPricingCatalogAdapter()

    @Bean
    fun costCalculationOperator(): CostCalculationOperator = DefaultCostCalculationOperator()

    @Bean
    @Profile("!test")
    @DependsOn("flywayInitializer")
    fun attemptAccountingPort(
        jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
        meterRegistry: MeterRegistry,
        @org.springframework.beans.factory.annotation.Value("\${gateway.accounting.completion-window:10s}") completionWindow: java.time.Duration,
    ): AttemptJournalPort = PostgresAttemptJournalAdapter(
        jdbc = jdbcTemplate,
        transactionManager = transactionManager,
        connectionWait = java.time.Duration.ofMillis(
            requireNotNull(jdbcTemplate.dataSource).unwrap(com.zaxxer.hikari.HikariDataSource::class.java).let {
                Math.addExact(it.connectionTimeout, it.validationTimeout)
            },
        ),
        meterRegistry = meterRegistry,
        completionWindow = completionWindow,
    )

    @Bean
    @Profile("!test")
    @DependsOn("flywayInitializer")
    fun requestAccountingPort(
        jdbcTemplate: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
        meterRegistry: MeterRegistry,
        @org.springframework.beans.factory.annotation.Value("\${gateway.accounting.completion-window:10s}") completionWindow: java.time.Duration,
    ): RequestAccountingPort = PostgresExecutionJournalAdapter(
        jdbc = jdbcTemplate,
        transactionManager = transactionManager,
        connectionWait = java.time.Duration.ofMillis(
            requireNotNull(jdbcTemplate.dataSource).unwrap(com.zaxxer.hikari.HikariDataSource::class.java).let {
                Math.addExact(it.connectionTimeout, it.validationTimeout)
            },
        ),
        completionWindow = completionWindow,
        meterRegistry = meterRegistry,
    )

    @Bean
    @Profile("test")
    fun requestAccountingPortForTest(): RequestAccountingPort = NoOpRequestAccountingPort

    @Bean
    fun providerInvoker(providers: ConfiguredProviders): ProviderInvokerPort =
        SpringAiProviderInvoker(providers.models)

    @Bean
    @Profile("!test")
    fun redisConnectivity(redisTemplate: StringRedisTemplate): String {
        val factory = requireNotNull(redisTemplate.connectionFactory) { "Redis connection factory is required" }
        factory.connection.use { connection ->
            check(!connection.ping().isNullOrBlank()) { "Redis ping returned no response" }
        }
        return "redis-ready"
    }

    @Bean
    fun attemptObserver(
        observationRegistry: ObservationRegistry,
        meterRegistry: MeterRegistry,
    ): AttemptObserverPort = MicrometerAttemptObserver(observationRegistry, meterRegistry)

    @Bean
    fun requestObserver(
        observationRegistry: ObservationRegistry,
        meterRegistry: MeterRegistry,
    ): RequestObserverPort = MicrometerRequestObserver(observationRegistry, meterRegistry)

    @Bean
    fun clientAuthenticationPort(environment: Environment): ClientAuthenticationPort =
        StaticApiKeyAuthenticationAdapter(
            enabled = environment.booleanProperty("gateway.security.enabled", true),
            clients = parseClients(environment.getProperty("gateway.security.clients").orEmpty()),
        )

    @Bean
    @Profile("!test")
    fun rateLimiterPort(
        environment: Environment,
        redisTemplate: StringRedisTemplate,
        meterRegistry: MeterRegistry,
    ): RateLimiterPort = RedisTokenBucketRateLimiter(
        redisTemplate = redisTemplate,
        enabled = environment.booleanProperty("gateway.rate-limit.enabled", true),
        requestsPerMinute = environment.intProperty("gateway.rate-limit.requests-per-minute", 120),
        burst = environment.intProperty("gateway.rate-limit.burst", 20),
        keyPrefix = environment.getProperty("gateway.rate-limit.key-prefix", "llm-gateway:rate-limit"),
        stateTtl = environment.durationProperty("gateway.rate-limit.state-ttl", java.time.Duration.ofMinutes(2)),
        meterRegistry = meterRegistry,
    )

    @Bean
    fun inputGuardrailPort(environment: Environment): InputGuardrailPort = ConfigurableInputGuardrailAdapter(
        enabled = environment.booleanProperty("gateway.guardrails.enabled", true),
        maxInputCharacters = environment.intProperty("gateway.guardrails.max-input-characters", 100_000),
        blockedPhrases = environment.getProperty("gateway.guardrails.blocked-phrases")
            .orEmpty()
            .split(',')
            .filter(String::isNotBlank),
    )

    @Bean
    fun outputGuardrailPort(environment: Environment): OutputGuardrailPort = ConfigurableOutputGuardrailAdapter(
        enabled = environment.booleanProperty("gateway.guardrails.enabled", true),
        maxOutputCharacters = environment.intProperty("gateway.guardrails.max-output-characters", 100_000),
        blockedPhrases = environment.getProperty("gateway.guardrails.output-blocked-phrases")
            ?.split(',')
            ?.filter(String::isNotBlank)
            .orEmpty(),
    )

    @Bean
    @Profile("!test")
    fun circuitBreakerPort(
        environment: Environment,
        redisTemplate: StringRedisTemplate,
        meterRegistry: MeterRegistry,
    ): CircuitBreakerPort = RedisCircuitBreakerAdapter(
        redisTemplate = redisTemplate,
        enabled = environment.booleanProperty("gateway.resilience.circuit-breaker.enabled", true),
        failureThreshold = environment.intProperty("gateway.resilience.circuit-breaker.failure-threshold", 3),
        openDuration = java.time.Duration.ofSeconds(
            environment.longProperty("gateway.resilience.circuit-breaker.open-duration-seconds", 10),
        ),
        keyPrefix = environment.getProperty("gateway.resilience.circuit-breaker.key-prefix", "llm-gateway:circuit"),
        stateTtl = environment.durationProperty(
            "gateway.resilience.circuit-breaker.state-ttl",
            java.time.Duration.ofMinutes(5),
        ),
        meterRegistry = meterRegistry,
    )

    private fun addOpenAiCompatible(
        properties: ProviderProperties,
        observationRegistry: ObservationRegistry,
        deployments: MutableList<Deployment>,
        models: MutableMap<DeploymentId, ChatModel>,
        vendor: Vendor,
        dialect: Dialect,
        defaultId: String,
        defaultModel: String,
        defaultBaseUrl: String,
        buildClients: Boolean,
    ) {
        if (!properties.enabled) return
        definitions(properties, defaultId, defaultModel).forEach { definition ->
            val key = definition.apiKey.ifBlank { properties.apiKey }
            if (key.isBlank()) return@forEach
            val id = DeploymentId(definition.id)
            val model = definition.model.ifBlank { properties.model.ifBlank { defaultModel } }
            if (buildClients) {
                val options = OpenAiChatOptions.builder()
                    .apiKey(key)
                    .baseUrl(definition.baseUrl.ifBlank { properties.baseUrl.ifBlank { defaultBaseUrl } })
                    .model(model)
                    .timeout(definition.timeout ?: properties.timeout)
                    .maxRetries(0)
                    .build()
                models[id] = OpenAiChatModel.builder()
                    .options(options)
                    .observationRegistry(observationRegistry)
                    .build()
            }
            deployments += definition.toDeployment(
                id = id,
                vendor = vendor,
                dialect = dialect,
                model = model,
                modelGroup = definition.modelGroup.ifBlank { properties.modelGroup },
            )
        }
    }

    private fun addBedrock(
        properties: ProviderProperties,
        observationRegistry: ObservationRegistry,
        deployments: MutableList<Deployment>,
        models: MutableMap<DeploymentId, ChatModel>,
        buildClients: Boolean,
    ) {
        if (!properties.enabled) return
        definitions(properties, "bedrock-default", "amazon.nova-micro-v1:0").forEach { definition ->
            val id = DeploymentId(definition.id)
            val model = definition.model.ifBlank { properties.model.ifBlank { "amazon.nova-micro-v1:0" } }
            if (buildClients) {
                val options = BedrockChatOptions.builder().model(model).build()
                models[id] = BedrockProxyChatModel.builder()
                    .region(Region.of(definition.region.ifBlank { properties.region.ifBlank { "us-east-1" } }))
                    .options(options)
                    .timeout(definition.timeout ?: properties.timeout)
                    .connectionTimeout(definition.connectionTimeout ?: properties.connectionTimeout)
                    .asyncReadTimeout(definition.readTimeout ?: properties.readTimeout)
                    .socketTimeout(definition.readTimeout ?: properties.readTimeout)
                    .connectionAcquisitionTimeout(
                        definition.connectionAcquisitionTimeout ?: properties.connectionAcquisitionTimeout,
                    )
                    .observationRegistry(observationRegistry)
                    .build()
            }
            deployments += definition.toDeployment(
                id = id,
                vendor = Vendor.AWS_BEDROCK,
                dialect = Dialect.BEDROCK_CONVERSE,
                model = model,
                modelGroup = definition.modelGroup.ifBlank { properties.modelGroup },
            )
        }
    }

    private fun definitions(
        properties: ProviderProperties,
        defaultId: String,
        defaultModel: String,
    ): List<ProviderDeploymentProperties> = properties.deployments.ifEmpty {
        listOf(ProviderDeploymentProperties().apply {
            id = defaultId
            model = properties.model.ifBlank { defaultModel }
            modelGroup = properties.modelGroup
            inputCostPer1kUsd = properties.inputCostPer1kUsd
            outputCostPer1kUsd = properties.outputCostPer1kUsd
            cacheReadInputCostPer1kUsd = properties.cacheReadInputCostPer1kUsd
            cacheWriteInputCostPer1kUsd = properties.cacheWriteInputCostPer1kUsd
        })
    }

    private fun ProviderDeploymentProperties.toDeployment(
        id: DeploymentId,
        vendor: Vendor,
        dialect: Dialect,
        model: String,
        modelGroup: String,
    ) = Deployment(
        id = id,
        vendor = vendor,
        dialect = dialect,
        modelGroup = ModelGroup(modelGroup.ifBlank { "default" }),
        model = model,
        priority = priority,
        weight = weight,
        enabled = enabled,
        supportsStreaming = supportsStreaming,
        inputCostPer1kUsd = inputCostPer1kUsd,
        outputCostPer1kUsd = outputCostPer1kUsd,
        cacheReadInputCostPer1kUsd = cacheReadInputCostPer1kUsd,
        cacheWriteInputCostPer1kUsd = cacheWriteInputCostPer1kUsd,
    )

    private fun parseClients(raw: String): Map<String, GatewayPrincipal> = raw
        .split(',')
        .mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val metadata = parts[1].split(':')
            if (metadata.size < 2) return@mapNotNull null
            parts[0].trim() to GatewayPrincipal(
                caller = metadata[0].trim(),
                tenant = metadata[1].trim(),
                administrator = metadata.getOrNull(2)?.trim()?.equals("admin", ignoreCase = true) == true,
            )
        }
        .toMap()
}

private fun Environment.booleanProperty(name: String, default: Boolean): Boolean =
    getProperty(name, Boolean::class.java) ?: default

private fun Environment.intProperty(name: String, default: Int): Int =
    getProperty(name, Int::class.java) ?: default

private fun Environment.longProperty(name: String, default: Long): Long =
    getProperty(name, Long::class.java) ?: default

private fun Environment.durationProperty(name: String, default: java.time.Duration): java.time.Duration =
    getProperty(name, java.time.Duration::class.java) ?: default
