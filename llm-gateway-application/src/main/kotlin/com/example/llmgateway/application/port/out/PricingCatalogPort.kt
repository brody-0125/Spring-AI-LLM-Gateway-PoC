package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.routing.Deployment
import java.time.Instant

interface PricingCatalogPort {
    fun resolve(deployment: Deployment, at: Instant): PricingSnapshot
}
