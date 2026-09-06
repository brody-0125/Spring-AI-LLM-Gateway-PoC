package com.example.llmgateway.domain.model


sealed interface AttemptOutcome {
    data class Success(val usage: Usage, val cost: Cost = Cost()) : AttemptOutcome
    data class Failure(val failureClass: FailureClass) : AttemptOutcome
    data object Cancelled : AttemptOutcome
}
