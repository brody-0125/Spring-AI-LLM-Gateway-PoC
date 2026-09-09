package com.example.llmgateway.application.port.`in`

import com.example.llmgateway.domain.policy.RegistrySeedResult
import com.example.llmgateway.domain.routing.Deployment

interface RegistrySeedCommandIn {
    fun seed(deployments: List<Deployment>): RegistrySeedResult
}
