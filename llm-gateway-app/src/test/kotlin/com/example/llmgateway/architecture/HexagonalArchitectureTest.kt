package com.example.llmgateway.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import io.kotest.core.spec.style.FunSpec

class HexagonalArchitectureTest : FunSpec() {

    init {
        test("production packages follow the gateway hexagonal dependency direction") {
            Konsist.scopeFromProduction().assertArchitecture {
                val core = Layer("Core", "com.example.llmgateway.core..")
                val domain = Layer("Domain", "com.example.llmgateway.domain..")
                val application = Layer("Application", "com.example.llmgateway.application..")
                val contract = Layer("Contract", "com.example.llmgateway.contract..")
                val adapterIn = Layer("AdapterIn", "com.example.llmgateway.adapter.in..")
                val adapterOut = Layer("AdapterOut", "com.example.llmgateway.adapter.out..")
                val bootstrap = Layer("Bootstrap", "com.example.llmgateway.bootstrap..")

                core.dependsOnNothing()
                domain.dependsOn(core)
                domain.doesNotDependOn(application, contract, adapterIn, adapterOut, bootstrap)
                contract.dependsOnNothing()
                application.dependsOn(core, domain)
                application.doesNotDependOn(contract, adapterIn, adapterOut, bootstrap)
                adapterIn.dependsOn(contract, application, domain, core)
                adapterIn.doesNotDependOn(adapterOut, bootstrap)
                adapterOut.dependsOn(application, domain, core)
                adapterOut.doesNotDependOn(contract, adapterIn, bootstrap)
                bootstrap.dependsOn(adapterIn, adapterOut, application, domain, core, contract)
            }
        }

        test("inner layers do not import framework or provider implementation types") {
            val protectedPackages = listOf(
                "com.example.llmgateway.core",
                "com.example.llmgateway.domain",
                "com.example.llmgateway.application",
            )
            val forbiddenImportPrefixes = listOf(
                "org.springframework.",
                "jakarta.servlet.",
                "reactor.",
                "kotlinx.coroutines.",
                "software.amazon.awssdk.",
                "com.openai.",
            )

            val violations = Konsist.scopeFromProduction().files.flatMap { file ->
                if (protectedPackages.none { file.packagee?.name.orEmpty().isInOrUnder(it) }) {
                    emptyList()
                } else {
                    file.imports
                        .filter { imported ->
                            val importedText = imported.text.trim().removePrefix("import ").trim()
                            forbiddenImportPrefixes.any(importedText::startsWith)
                        }
                        .map { imported -> "${file.path}: ${imported.text.trim()}" }
                }
            }

            check(violations.isEmpty()) {
                "Inner-layer framework/provider imports are not allowed: ${violations.joinToString()}"
            }
        }

        test("hexagonal ports and operations follow the naming contract") {
            val production = Konsist.scopeFromProduction()

            val inputPortViolations = production.interfaces()
                .filter { it.packagee?.name.orEmpty().isInOrUnder("com.example.llmgateway.application.port.in") }
                .filterNot { it.hasNameEndingWith("QueryIn") || it.hasNameEndingWith("CommandIn") }
                .map { it.fullyQualifiedName }

            val outputPortViolations = production.interfaces()
                .filter { it.packagee?.name.orEmpty().isInOrUnder("com.example.llmgateway.application.port.out") }
                .filterNot { it.hasNameEndingWith("Port") }
                .map { it.fullyQualifiedName }

            val operationViolations = production.classesAndInterfacesAndObjects()
                .filter { it.packagee?.name.orEmpty().isInOrUnder("com.example.llmgateway.application.operation") }
                .filterNot { it.hasNameEndingWith("Operation") }
                .map { it.fullyQualifiedName }

            val useCaseDeclarations = production.classesAndInterfacesAndObjects()
                .filter { it.hasNameEndingWith("UseCase") }
                .map { it.fullyQualifiedName }

            check(inputPortViolations.isEmpty()) {
                "Input ports must end with QueryIn or CommandIn: ${inputPortViolations.joinToString()}"
            }
            check(outputPortViolations.isEmpty()) {
                "Output ports must end with Port: ${outputPortViolations.joinToString()}"
            }
            check(operationViolations.isEmpty()) {
                "Application operations must end with Operation: ${operationViolations.joinToString()}"
            }
            check(useCaseDeclarations.isEmpty()) {
                "UseCase declarations are forbidden; split the input port into QueryIn or CommandIn: " +
                    useCaseDeclarations.joinToString()
            }
        }
    }
}

private fun String.isInOrUnder(packageName: String): Boolean =
    this == packageName || startsWith("$packageName.")
