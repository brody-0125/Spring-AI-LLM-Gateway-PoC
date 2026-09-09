package com.example.llmgateway.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import com.example.llmgateway.application.port.`in`.RegistrySeedCommandIn
import com.example.llmgateway.adapter.out.springai.ConfiguredProviders

@SpringBootApplication(scanBasePackages = ["com.example.llmgateway"])
class GatewayApplication

fun main(args: Array<String>) {
    if (args.firstOrNull() == "seed-registry") {
        require(args.none { it.startsWith("--spring.main.web-application-type") }) {
            "The seed command cannot enable a web server"
        }
        SpringApplicationBuilder(GatewayApplication::class.java)
            .profiles("registry-seed")
            .web(WebApplicationType.NONE)
            .run(*(args.drop(1) + "--spring.main.web-application-type=none").toTypedArray())
            .use { context ->
                val result = context.getBean(RegistrySeedCommandIn::class.java)
                    .seed(context.getBean(ConfiguredProviders::class.java).deployments)
                println("Registry seed completed: created=${result.created} existing=${result.existing}")
            }
        return
    }
    runApplication<GatewayApplication>(*args)
}
