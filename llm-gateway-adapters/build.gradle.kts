plugins {
    kotlin("plugin.spring")
    `java-library`
}

// Integration tests execute the exact migrations shipped by the executable app.
sourceSets.test {
    resources.srcDir(project(":llm-gateway-app").file("src/main/resources"))
    resources.include("db/migration/**")
}

dependencies {
    implementation(project(":llm-gateway-core"))
    implementation(project(":llm-gateway-domain"))
    implementation(project(":llm-gateway-application"))
    implementation("org.springframework.ai:spring-ai-openai")
    implementation("org.springframework.ai:spring-ai-bedrock-converse")
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-observation")
    implementation("org.springframework:spring-context")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core-jvm:6.0.7")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.postgresql:postgresql")
}
