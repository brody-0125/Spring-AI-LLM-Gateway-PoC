package com.example.llmgateway.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

class OpenApiErrorContractTest : FunSpec({
    test("public contract parses and durable completion failure has a non-retryable example") {
        val resource = checkNotNull(javaClass.getResourceAsStream("/openapi.yaml"))
        val specification = resource.use { Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(it) }
        specification["openapi"] shouldBe "3.0.3"
        fun Map<*, *>.objectAt(name: String) = get(name) as Map<*, *>
        val response = specification.objectAt("paths").objectAt("/v1/chat/completions")
            .objectAt("post").objectAt("responses").objectAt("503")
        val error = response.objectAt("content").objectAt("application/json").objectAt("example").objectAt("error")
        error["code"] shouldBe "OUTCOME_UNKNOWN"
        error["retryable"] shouldBe false
        error.containsKey("retry_after_seconds") shouldBe false
        response.objectAt("headers").containsKey("Retry-After") shouldBe false
    }
})
