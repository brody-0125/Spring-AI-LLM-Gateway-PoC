plugins {
    `java-library`
}

dependencies {
    api(project(":llm-gateway-domain"))
    api(project(":llm-gateway-core"))
    testImplementation("io.kotest:kotest-runner-junit5-jvm:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core-jvm:6.0.7")
}
