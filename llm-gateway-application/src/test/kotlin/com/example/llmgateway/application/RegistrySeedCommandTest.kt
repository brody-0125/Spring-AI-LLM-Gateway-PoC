package com.example.llmgateway.application

import com.example.llmgateway.application.port.out.RegistrySeedPort
import com.example.llmgateway.application.service.DefaultRegistrySeedCommandService
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.policy.RegistrySeedResult
import com.example.llmgateway.domain.routing.Deployment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RegistrySeedCommandTest : FunSpec({
    test("seed validates configuration before performing an explicit write") {
        val deployment = Deployment(DeploymentId("seed"), Vendor.OPENAI, Dialect.OPENAI, ModelGroup("default"), "test")
        var writes = 0
        val command = DefaultRegistrySeedCommandService(RegistrySeedPort { values ->
            writes++
            values shouldBe listOf(deployment)
            RegistrySeedResult(1, 0)
        })
        shouldThrow<IllegalArgumentException> { command.seed(emptyList()) }
        shouldThrow<IllegalArgumentException> { command.seed(listOf(deployment, deployment)) }
        writes shouldBe 0
        command.seed(listOf(deployment)) shouldBe RegistrySeedResult(1, 0)
        writes shouldBe 1
    }
})
