package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.model.FailureClass

class AttemptFailureException(
    val failureClass: FailureClass,
    val emitted: Boolean = false,
    cause: Throwable,
) : RuntimeException(cause.message, cause)
