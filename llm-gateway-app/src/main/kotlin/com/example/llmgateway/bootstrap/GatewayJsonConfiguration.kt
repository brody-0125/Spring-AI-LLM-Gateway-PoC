package com.example.llmgateway.bootstrap

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.DeserializationFeature

@Configuration(proxyBeanMethods = false)
class GatewayJsonConfiguration {
    @Bean
    fun gatewayJsonMapperCustomizer(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
}
