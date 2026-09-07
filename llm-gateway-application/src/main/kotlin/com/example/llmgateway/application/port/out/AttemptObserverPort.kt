package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.AttemptOutcome

interface AttemptObserverPort {
    fun onStart(context: AttemptContext)

    fun onFirstToken(context: AttemptContext) = Unit

    fun onStop(context: AttemptContext, outcome: AttemptOutcome)
}
