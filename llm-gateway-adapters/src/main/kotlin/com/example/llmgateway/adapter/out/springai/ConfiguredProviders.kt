package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.domain.routing.Deployment
import org.springframework.ai.chat.model.ChatModel

data class ConfiguredProviders(
    val deployments: List<Deployment>,
    val models: Map<DeploymentId, ChatModel>,
)
