package com.example.llmgateway.application.policy

class GatewayDeadlineExceededException(cause: Throwable) : RuntimeException(
    "Gateway request deadline exceeded",
    cause,
)
