package com.example.llmgateway.domain.execution

import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.error.FailureClass

data class AttemptFailure(
    val failureClass: FailureClass,
    val usage: Usage = Usage(available = false),
    val cost: Cost = Cost(),
    val providerRequestId: String? = null,
) : AttemptOutcome
