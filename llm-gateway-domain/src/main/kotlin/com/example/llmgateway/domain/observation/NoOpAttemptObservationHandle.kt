package com.example.llmgateway.domain.observation

import com.example.llmgateway.domain.execution.AttemptOutcome

object NoOpAttemptObservationHandle : AttemptObservationHandle,
    ObservationHandle<AttemptOutcome> by NoOpObservationHandle
