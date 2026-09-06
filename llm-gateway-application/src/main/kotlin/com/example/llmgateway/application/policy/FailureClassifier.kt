package com.example.llmgateway.application.policy

import com.example.llmgateway.domain.model.FailureClass

interface FailureClassifier {
    fun classify(error: Throwable): FailureClass
}
