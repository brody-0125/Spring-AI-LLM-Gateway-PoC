package com.example.llmgateway.application.service

import com.example.llmgateway.application.port.`in`.RegistrySeedCommandIn
import com.example.llmgateway.application.port.out.RegistrySeedPort
import com.example.llmgateway.domain.policy.RegistrySeedResult
import com.example.llmgateway.domain.routing.Deployment

class DefaultRegistrySeedCommandService(private val seedPort: RegistrySeedPort) : RegistrySeedCommandIn {
    override fun seed(deployments: List<Deployment>): RegistrySeedResult {
        val seed = deployments.toList()
        require(seed.isNotEmpty()) { "No configured deployments to seed" }
        require(seed.map { it.id }.distinct().size == seed.size) { "Deployment IDs must be unique" }
        return seedPort.seed(seed)
    }
}
