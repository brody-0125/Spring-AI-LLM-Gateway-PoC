package com.example.llmgateway.application.operator

fun interface OutputGuardrailSession {
    fun inspect(chunk: String)
}
