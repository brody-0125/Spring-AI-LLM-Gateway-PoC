package com.example.llmgateway.app

import com.example.llmgateway.adapter.out.springai.ConfiguredProviders
import com.example.llmgateway.application.port.`in`.RegistrySeedCommandIn
import com.example.llmgateway.application.port.out.RegistrySeedPort
import com.example.llmgateway.bootstrap.GatewayApplication
import com.example.llmgateway.domain.policy.RegistrySeedResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext

class RegistrySeedBootstrapIntegrationTest : FunSpec({
    test("seed CLI rejects a web-server override before creating its context") {
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            com.example.llmgateway.bootstrap.main(arrayOf("seed-registry", "--spring.main.web-application-type=servlet"))
        }
    }

    test("seed context wires the explicit command without a web server or provider clients") {
        var writes = 0
        val seedPort = RegistrySeedPort {
            writes++
            it.size shouldBe 1
            RegistrySeedResult(1, 0)
        }
        SpringApplicationBuilder(GatewayApplication::class.java, TestDistributedStateConfiguration::class.java)
            .profiles("test", "registry-seed")
            .web(WebApplicationType.NONE)
            .initializers(ApplicationContextInitializer<ConfigurableApplicationContext> {
                it.beanFactory.registerSingleton("testRegistrySeedPort", seedPort)
            })
            .run(
                "--spring.main.web-application-type=none",
                "--gateway.providers.openai.api-key=test-only-key",
                "--gateway.providers.openai.enabled=true",
                "--gateway.providers.openai.input-cost-per-1k-usd=",
                "--gateway.providers.openai.output-cost-per-1k-usd=0",
                "--gateway.providers.openai.cache-read-input-cost-per-1k-usd=0.000000000000001",
                "--gateway.providers.openai.cache-write-input-cost-per-1k-usd=",
                "--gateway.providers.openrouter.enabled=false",
                "--gateway.providers.bedrock.enabled=false",
            ).use { context ->
                writes shouldBe 0
                context.environment.getProperty("local.server.port") shouldBe null
                val providers = context.getBean(ConfiguredProviders::class.java)
                providers.deployments.single().inputCostPer1kUsd shouldBe null
                providers.deployments.single().outputCostPer1kUsd shouldBe java.math.BigDecimal.ZERO
                providers.deployments.single().cacheReadInputCostPer1kUsd shouldBe java.math.BigDecimal("0.000000000000001")
                providers.deployments.single().cacheWriteInputCostPer1kUsd shouldBe null
                context.getBean(RegistrySeedCommandIn::class.java).seed(providers.deployments) shouldBe RegistrySeedResult(1, 0)
                writes shouldBe 1
            }
    }
})
