package com.example.llmgateway.domain.execution

enum class AttemptKind {
    INITIAL,
    RETRY,
    FALLBACK,
}
