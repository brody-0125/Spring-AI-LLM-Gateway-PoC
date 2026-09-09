package com.example.llmgateway.domain.observation

import com.example.llmgateway.domain.execution.AttemptOutcome

interface AttemptObservationHandle : ObservationHandle<AttemptOutcome> {
    fun firstToken() = Unit
}
