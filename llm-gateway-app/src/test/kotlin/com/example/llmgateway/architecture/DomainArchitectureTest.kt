package com.example.llmgateway.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.core.exception.KoAssertionFailedException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec

class DomainArchitectureTest : FunSpec({
    val allowedDependencies = mapOf(
        "accounting" to emptySet<String>(),
        "identity" to emptySet(),
        "policy" to emptySet(),
        "error" to emptySet(),
        "stream" to emptySet(),
        "routing" to setOf("accounting"),
        "execution" to setOf("routing", "accounting", "error"),
        "inference.chat" to setOf("accounting"),
        "observation" to setOf("execution", "stream"),
    )
    val prefix = "com.example.llmgateway.domain."
    val production = Konsist.scopeFromProduction()

    test("every domain file belongs to an explicitly reviewed responsibility") {
        val domainFiles = production.files.filter {
            val name = it.packagee?.name.orEmpty()
            name == prefix.removeSuffix(".") || name.startsWith(prefix)
        }
        check(domainFiles.isNotEmpty()) { "Domain source scope is empty" }
        val violations = domainFiles.filter { it.packagee?.name?.removePrefix(prefix) !in allowedDependencies }
        check(violations.isEmpty()) { "Unreviewed domain packages: ${violations.map { it.path }}" }
    }

    test("domain responsibilities have one-way dependencies and accounting is independent of execution and telemetry") {
        production.assertArchitecture {
            val layers = allowedDependencies.keys.associateWith { Layer(it, "$prefix$it..") }
            allowedDependencies.forEach { (owner, allowed) ->
                val forbidden = layers.filterKeys { it != owner && it !in allowed }.values.toSet()
                layers.getValue(owner).doesNotDependOn(forbidden)
            }
        }
    }

    test("a telemetry dependency in accounting is rejected by the architecture checker") {
        val fixture = Konsist.scopeFromDirectory(
            "llm-gateway-app/src/test/resources/architecture/domain-boundaries",
        )
        val failure = shouldThrow<KoAssertionFailedException> {
            fixture.assertArchitecture {
                val accounting = Layer("Accounting", "${prefix}accounting..")
                val observation = Layer("Observation", "${prefix}observation..")
                accounting.doesNotDependOn(observation)
            }
        }
        check(failure.message.orEmpty().contains("Ledger")) { "Expected the fixture's forbidden dependency" }
    }
})
