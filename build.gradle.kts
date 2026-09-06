import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

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
