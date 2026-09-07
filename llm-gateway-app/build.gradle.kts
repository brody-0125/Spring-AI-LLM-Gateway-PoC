plugins {
    id("org.springframework.boot")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":llm-gateway-contract"))
    implementation(project(":llm-gateway-core"))
    implementation(project(":llm-gateway-domain"))
    implementation(project(":llm-gateway-application"))
    implementation(project(":llm-gateway-adapters"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("org.postgresql:postgresql")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core-jvm:6.0.7")
    testImplementation("io.kotest:kotest-extensions-spring:6.0.7")
    testImplementation("com.lemonappdev:konsist:0.17.3")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
}

tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
    archiveFileName.set("llm-gateway.jar")
}
