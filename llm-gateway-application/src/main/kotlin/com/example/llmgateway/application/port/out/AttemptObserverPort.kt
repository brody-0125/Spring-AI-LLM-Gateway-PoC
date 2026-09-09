package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.observation.AttemptObservationHandle

interface AttemptObserverPort {
    fun start(context: AttemptContext): AttemptObservationHandle
}
