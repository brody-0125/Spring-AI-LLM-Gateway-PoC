package com.example.llmgateway.domain.error

enum class ProviderFailurePhase {
    UNKNOWN,
    CONNECT,
    WRITE,
    FIRST_BYTE,
    READ,
    STREAM_IDLE,
    COMPLETE,
}
