package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.AttemptOutcome

object NoOpAttemptAccountingPort : AttemptAccountingPort {
    override fun record(context: AttemptContext, outcome: AttemptOutcome) = Unit
}
