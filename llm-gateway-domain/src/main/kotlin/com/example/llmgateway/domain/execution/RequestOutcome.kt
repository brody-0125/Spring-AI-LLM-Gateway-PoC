package com.example.llmgateway.domain.execution

data class RequestOutcome(
    val status: RequestOutcomeStatus,
    val errorType: String? = null,
    val errorCode: String? = null,
)
