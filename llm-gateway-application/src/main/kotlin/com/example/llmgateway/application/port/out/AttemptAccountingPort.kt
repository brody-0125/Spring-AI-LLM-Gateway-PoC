package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptOutcome

interface AttemptAccountingPort {
    fun record(context: AttemptContext, outcome: AttemptOutcome)
}
