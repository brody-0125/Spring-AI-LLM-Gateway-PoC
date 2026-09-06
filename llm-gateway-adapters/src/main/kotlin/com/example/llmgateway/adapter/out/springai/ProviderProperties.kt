package com.example.llmgateway.adapter.out.springai

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
    var maxRetries: Int = 0
    var deployments: List<ProviderDeploymentProperties> = emptyList()
}
