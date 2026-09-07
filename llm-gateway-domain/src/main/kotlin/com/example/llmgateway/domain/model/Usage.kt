package com.example.llmgateway.domain.model


data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
    val cacheWriteInputTokens: Long = 0,
    val reasoningOutputTokens: Long = 0,
    val available: Boolean = true,
) {
    init {
        require(inputTokens >= 0) { "input tokens must not be negative" }
        require(outputTokens >= 0) { "output tokens must not be negative" }
        require(cacheReadInputTokens >= 0) { "cache read tokens must not be negative" }
        require(cacheWriteInputTokens >= 0) { "cache write tokens must not be negative" }
        require(reasoningOutputTokens >= 0) { "reasoning tokens must not be negative" }
        require(reasoningOutputTokens <= outputTokens) { "reasoning tokens must not exceed output tokens" }
    }

    val totalTokens: Long get() = inputTokens + outputTokens

    val regularInputTokens: Long
        get() = (inputTokens - cacheReadInputTokens - cacheWriteInputTokens).coerceAtLeast(0)

    fun mergeCumulative(next: Usage): Usage = Usage(
        inputTokens = maxOf(inputTokens, next.inputTokens),
        outputTokens = maxOf(outputTokens, next.outputTokens),
        cacheReadInputTokens = maxOf(cacheReadInputTokens, next.cacheReadInputTokens),
        cacheWriteInputTokens = maxOf(cacheWriteInputTokens, next.cacheWriteInputTokens),
        reasoningOutputTokens = maxOf(reasoningOutputTokens, next.reasoningOutputTokens),
        available = available || next.available,
    )
}
