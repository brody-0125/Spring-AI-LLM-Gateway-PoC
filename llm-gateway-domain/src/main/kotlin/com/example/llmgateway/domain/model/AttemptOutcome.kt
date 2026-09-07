package com.example.llmgateway.domain.model


sealed interface AttemptOutcome {
    data class Success(val usage: Usage, val cost: Cost = Cost()) : AttemptOutcome
    data class Failure(
        val failureClass: FailureClass,
        val usage: Usage = Usage(available = false),
        val cost: Cost = Cost(),
    ) : AttemptOutcome
    data class CancelledWithUsage(
        val usage: Usage,
        val cost: Cost = Cost(),
    ) : AttemptOutcome
    data object Cancelled : AttemptOutcome
}
