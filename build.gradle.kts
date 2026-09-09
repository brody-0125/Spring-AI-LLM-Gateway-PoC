import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.spring") version "2.3.0" apply false
    id("org.springframework.boot") version "4.0.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

allprojects {
    group = "com.example.llmgateway"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")
            mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
        }
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        inputs.property("RUN_TESTCONTAINERS", System.getenv("RUN_TESTCONTAINERS") ?: "false")
    }

    val innerProjects = mapOf(
        ":llm-gateway-core" to emptySet<String>(),
        ":llm-gateway-domain" to setOf(":llm-gateway-core"),
        ":llm-gateway-application" to setOf(":llm-gateway-core", ":llm-gateway-domain"),
    )
    innerProjects[path]?.let { allowedProjects ->
        val innerProjectPath = path
        val inspectedClasspaths = listOf("compileClasspath", "runtimeClasspath")
            .associateWith { configurations.getByName(it) }
        val verifyInnerDependencies = tasks.register("verifyInnerDependencies") {
            group = "verification"
            description = "Checks resolved inner-layer compile and runtime dependency boundaries."
            doLast {
                val violations = mutableListOf<String>()
                inspectedClasspaths.forEach { (classpath, configuration) ->
                    val resolution = configuration.incoming.resolutionResult
                    resolution.allDependencies.filterIsInstance<UnresolvedDependencyResult>().forEach {
                        violations += "$classpath: unresolved dependency ${it.requested.displayName}"
                    }
                    resolution.allComponents.forEach { component ->
                        when (val id = component.id) {
                            is ProjectComponentIdentifier -> {
                                if (id.projectPath != innerProjectPath && id.projectPath !in allowedProjects) {
                                    violations += "$classpath: forbidden project ${id.projectPath}"
                                }
                            }
                            is ModuleComponentIdentifier -> {
                                val kotlinRuntime = id.group == "org.jetbrains.kotlin" && id.module in setOf(
                                    "kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8",
                                    "kotlin-stdlib-common", "kotlin-reflect", "kotlin-bom",
                                )
                                val annotations = id.group == "org.jetbrains" && id.module == "annotations"
                                val crossCutting = id.group in setOf(
                                    "org.slf4j", "ch.qos.logback",
                                    "com.fasterxml.jackson.core", "com.fasterxml.jackson.module", "com.fasterxml.jackson.datatype",
                                    "tools.jackson.core", "tools.jackson.module", "tools.jackson.datatype",
                                )
                                if (!kotlinRuntime && !annotations && !crossCutting) {
                                    violations += "$classpath: forbidden module ${id.group}:${id.module}:${id.version}"
                                }
                            }
                        }
                    }
                }
                check(violations.isEmpty()) {
                    "Inner dependency boundary violated for $innerProjectPath:\n${violations.distinct().sorted().joinToString("\n")}"
                }
                logger.lifecycle("$innerProjectPath: compile/runtime dependency boundaries verified")
            }
        }
        tasks.named("compileKotlin") { dependsOn(verifyInnerDependencies) }
        tasks.named("check") { dependsOn(verifyInnerDependencies) }
        tasks.withType<Test>().configureEach { dependsOn(verifyInnerDependencies) }
    }

}

dependencies {
    kover(project(":llm-gateway-contract"))
    kover(project(":llm-gateway-core"))
    kover(project(":llm-gateway-domain"))
    kover(project(":llm-gateway-application"))
    kover(project(":llm-gateway-adapters"))
    kover(project(":llm-gateway-app"))
}

kover {
    reports {
        total {
            xml {
                onCheck = false
            }
            html {
                onCheck = false
            }
            log {
                onCheck = false
            }
            verify {
                rule {
                    minBound(68)
                }
            }
        }
    }
}
