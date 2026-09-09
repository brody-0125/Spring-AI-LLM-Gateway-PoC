package com.example.llmgateway.adapter.out.springai

import java.math.BigDecimal
import java.time.Duration

class ProviderProperties {
    var enabled: Boolean = true
    var apiKey: String = ""
    var model: String = ""
    var modelGroup: String = "default"
    var baseUrl: String = ""
    var region: String = ""
    var timeout: Duration = Duration.ofSeconds(60)
    var connectionTimeout: Duration = Duration.ofSeconds(5)
    var readTimeout: Duration = Duration.ofSeconds(60)
    var connectionAcquisitionTimeout: Duration = Duration.ofSeconds(5)
    var inputCostPer1kUsd: BigDecimal? = null
    var outputCostPer1kUsd: BigDecimal? = null
    var cacheReadInputCostPer1kUsd: BigDecimal? = null
    var cacheWriteInputCostPer1kUsd: BigDecimal? = null
    var deployments: List<ProviderDeploymentProperties> = emptyList()
}
