package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.springai.GatewayProviderProperties
import com.example.llmgateway.adapter.out.springai.SpringAiVendorConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.observation.ObservationRegistry
import org.springframework.core.env.StandardEnvironment

class RegistrySeedConfigurationTest : FunSpec({
    test("seed configuration selects metadata for all vendors without constructing provider clients") {
        val environment = StandardEnvironment().apply { setActiveProfiles("registry-seed") }
        val properties = GatewayProviderProperties().apply {
            openai.apiKey = "test-only-key"
            openrouter.apiKey = "test-only-key"
            bedrock.enabled = true
        }
        val configured = SpringAiVendorConfiguration().configuredProviders(
            properties, ObservationRegistry.create(), environment,
        )
        configured.deployments.size shouldBe 3
        configured.models.isEmpty() shouldBe true
        configured.deployments.all { it.inputCostPer1kUsd == null && it.outputCostPer1kUsd == null } shouldBe true
    }

    test("typed binding preserves omitted, empty, zero and nested configured prices") {
        val source = org.springframework.boot.context.properties.source.MapConfigurationPropertySource(mapOf(
            "gateway.providers.openai.input-cost-per-1k-usd" to "",
            "gateway.providers.openai.output-cost-per-1k-usd" to "0",
            "gateway.providers.openrouter.deployments[0].id" to "free",
            "gateway.providers.openrouter.deployments[0].input-cost-per-1k-usd" to "0",
        ))
        val properties = org.springframework.boot.context.properties.bind.Binder(source)
            .bind("gateway.providers", org.springframework.boot.context.properties.bind.Bindable.of(GatewayProviderProperties::class.java)).get()
        properties.openai.inputCostPer1kUsd shouldBe null
        properties.openai.outputCostPer1kUsd shouldBe java.math.BigDecimal.ZERO
        properties.openai.cacheReadInputCostPer1kUsd shouldBe null
        properties.openrouter.deployments.single().inputCostPer1kUsd shouldBe java.math.BigDecimal.ZERO
        properties.openrouter.deployments.single().outputCostPer1kUsd shouldBe null
    }
})
