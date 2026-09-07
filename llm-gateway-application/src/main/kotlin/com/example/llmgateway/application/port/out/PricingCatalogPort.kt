package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.PricingSnapshot
import java.time.Instant

interface PricingCatalogPort {
    fun resolve(deployment: Deployment, at: Instant): PricingSnapshot
}
