package com.example.llmgateway.application.operator

class AttemptAccountingException(cause: Throwable) : RuntimeException(
    "The provider attempt could not be durably accounted",
    cause,
)
