package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.policy.RegistrySeedResult
import com.example.llmgateway.domain.routing.Deployment

fun interface RegistrySeedPort {
    fun seed(deployments: List<Deployment>): RegistrySeedResult
}
