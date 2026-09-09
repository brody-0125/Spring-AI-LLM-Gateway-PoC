package com.example.llmgateway.bootstrap

import com.example.llmgateway.application.port.`in`.RegistrySeedCommandIn
import com.example.llmgateway.application.port.out.RegistrySeedPort
import com.example.llmgateway.application.service.DefaultRegistrySeedCommandService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
@Profile("registry-seed")
class RegistrySeedConfiguration {
    @Bean
    fun registrySeedCommandIn(port: RegistrySeedPort): RegistrySeedCommandIn = DefaultRegistrySeedCommandService(port)
}
