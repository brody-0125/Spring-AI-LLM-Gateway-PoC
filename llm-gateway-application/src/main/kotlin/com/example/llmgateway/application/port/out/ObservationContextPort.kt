package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.observation.ObservationContext

fun interface ObservationContextPort {
    fun capture(): ObservationContext
}
