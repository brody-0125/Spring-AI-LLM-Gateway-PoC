package com.example.llmgateway.domain.execution

import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.accounting.Usage

data class AttemptCancelledWithUsage(
    val usage: Usage,
    val cost: Cost = Cost(),
    val providerRequestId: String? = null,
) : AttemptOutcome
