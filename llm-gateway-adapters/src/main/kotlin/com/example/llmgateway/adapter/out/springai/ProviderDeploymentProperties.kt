package com.example.llmgateway.adapter.out.springai

import java.math.BigDecimal
import java.time.Duration

class ProviderDeploymentProperties {
    var id: String = ""
    var apiKey: String = ""
    var model: String = ""
    var modelGroup: String = ""
    var baseUrl: String = ""
    var region: String = ""
    var enabled: Boolean = true
    var priority: Int = 0
    var weight: Int = 1
    var supportsStreaming: Boolean = true
    var inputCostPer1kUsd: BigDecimal = BigDecimal.ZERO
    var outputCostPer1kUsd: BigDecimal = BigDecimal.ZERO
    var cacheReadInputCostPer1kUsd: BigDecimal = BigDecimal.ZERO
    var cacheWriteInputCostPer1kUsd: BigDecimal = BigDecimal.ZERO
    var timeout: Duration? = null
    var connectionTimeout: Duration? = null
    var readTimeout: Duration? = null
    var connectionAcquisitionTimeout: Duration? = null
}
