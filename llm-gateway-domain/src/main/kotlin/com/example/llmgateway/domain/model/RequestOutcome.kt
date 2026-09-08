package com.example.llmgateway.domain.model

enum class RequestOutcomeStatus {
    SUCCESS,
    FAILURE,
    CANCELLED,
}

data class RequestOutcome(
    val status: RequestOutcomeStatus,
    val errorType: String? = null,
    val errorCode: String? = null,
)
