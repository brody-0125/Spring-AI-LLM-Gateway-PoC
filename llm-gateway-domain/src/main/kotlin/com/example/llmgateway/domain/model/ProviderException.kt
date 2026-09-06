package com.example.llmgateway.domain.model
import com.example.llmgateway.core.primitive.Vendor


class ProviderException(
    val vendor: Vendor,
    val statusCode: Int? = null,
    val providerCode: String? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
