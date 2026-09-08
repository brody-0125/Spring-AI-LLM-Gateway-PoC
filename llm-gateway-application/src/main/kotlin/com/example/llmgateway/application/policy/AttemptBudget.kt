package com.example.llmgateway.application.policy

class AttemptBudget(
    private val maxTotalAttempts: Int,
    private val maxFallbacks: Int,
) {
    var attempts: Int = 0
        private set

    var fallbacks: Int = 0
        private set

    fun startAttempt(): Boolean {
        if (attempts >= maxTotalAttempts) return false
        attempts += 1
        return true
    }

    fun startFallback(): Boolean {
        if (fallbacks >= maxFallbacks || attempts >= maxTotalAttempts) return false
        fallbacks += 1
        return true
    }
}
