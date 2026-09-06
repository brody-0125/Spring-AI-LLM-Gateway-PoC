import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "spring-ai-llm-gateway"
include("llm-gateway-contract", "llm-gateway-core", "llm-gateway-domain", "llm-gateway-application", "llm-gateway-adapters", "llm-gateway-app")
