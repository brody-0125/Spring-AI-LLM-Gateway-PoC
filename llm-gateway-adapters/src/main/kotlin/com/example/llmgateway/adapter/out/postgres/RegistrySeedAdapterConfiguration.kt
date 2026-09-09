package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.RegistrySeedPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
@Profile("registry-seed & !test")
class RegistrySeedAdapterConfiguration {
    @Bean
    @DependsOn("flywayInitializer")
    fun registrySeedPort(jdbc: JdbcTemplate, transactionManager: PlatformTransactionManager): RegistrySeedPort =
        PostgresRegistrySeedAdapter(jdbc, TransactionTemplate(transactionManager))
}
