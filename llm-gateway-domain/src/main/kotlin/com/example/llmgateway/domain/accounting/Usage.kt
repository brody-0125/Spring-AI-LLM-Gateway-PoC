package com.example.llmgateway.domain.accounting

import java.util.Collections

/** Canonical token totals include cache input and reasoning output; those are not extra total tokens. */
class Usage(components: List<UsageComponent>) {
    val components: List<UsageComponent> = Collections.unmodifiableList(components.toList())

    constructor(
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        cacheReadInputTokens: Long = 0,
        cacheWriteInputTokens: Long = 0,
        reasoningOutputTokens: Long = 0,
        available: Boolean = true,
    ) : this(listOf(
        UsageType.INPUT_TOKENS to inputTokens,
        UsageType.OUTPUT_TOKENS to outputTokens,
        UsageType.CACHE_READ_INPUT_TOKENS to cacheReadInputTokens,
        UsageType.CACHE_WRITE_INPUT_TOKENS to cacheWriteInputTokens,
        UsageType.REASONING_OUTPUT_TOKENS to reasoningOutputTokens,
    ).map { (type, value) ->
        require(value >= 0) { "usage quantity must not be negative" }
        UsageComponent(UsageKey(type), value.takeIf { available })
    })

    init {
        require(this.components.map { it.key }.distinct().size == this.components.size) {
            "usage component keys must be unique"
        }
        UsageType.entries.forEach { knownQuantity(it) }
        require(inputTokens <= Long.MAX_VALUE - outputTokens) { "total tokens exceed the supported range" }
        require(cacheReadInputTokens <= Long.MAX_VALUE - cacheWriteInputTokens) {
            "combined cache tokens exceed the supported range"
        }
        quantity(UsageType.OUTPUT_TOKENS)?.let {
            require(reasoningOutputTokens <= it) { "reasoning tokens must not exceed output tokens" }
        }
    }

    val available: Boolean get() = components.any { it.quantity != null }
    val hasCompleteTokenTotals: Boolean
        get() = quantity(UsageType.INPUT_TOKENS) != null && quantity(UsageType.OUTPUT_TOKENS) != null
    val inputTokens: Long get() = knownQuantity(UsageType.INPUT_TOKENS)
    val outputTokens: Long get() = knownQuantity(UsageType.OUTPUT_TOKENS)
    val cacheReadInputTokens: Long get() = knownQuantity(UsageType.CACHE_READ_INPUT_TOKENS)
    val cacheWriteInputTokens: Long get() = knownQuantity(UsageType.CACHE_WRITE_INPUT_TOKENS)
    val reasoningOutputTokens: Long get() = knownQuantity(UsageType.REASONING_OUTPUT_TOKENS)
    val totalTokens: Long get() = inputTokens + outputTokens
    val regularInputTokens: Long get() = (inputTokens - cacheReadInputTokens - cacheWriteInputTokens).coerceAtLeast(0)

    /** Absent or partially measured types are unknown, not zero. */
    fun quantity(type: UsageType): Long? {
        val selected = components.filter { it.key.type == type }
        return if (selected.isEmpty() || selected.any { it.quantity == null }) null else knownQuantity(type)
    }

    private fun knownQuantity(type: UsageType): Long = components.filter { it.key.type == type }
        .fold(0L) { total, component ->
            val value = component.quantity ?: 0
            require(total <= Long.MAX_VALUE - value) { "usage type total exceeds the supported range" }
            total + value
        }

    fun mergeCumulative(next: Usage): Usage {
        val merged = components.associateByTo(linkedMapOf()) { it.key }
        next.components.forEach { value ->
            val old = merged[value.key]
            merged[value.key] = when {
                old == null || old.quantity == null -> value
                value.quantity == null -> old
                old.source == UsageSource.PROVIDER_REPORTED && value.source != old.source -> old
                value.source == UsageSource.PROVIDER_REPORTED && old.source != value.source -> value
                value.quantity >= old.quantity -> value
                else -> old
            }
        }
        return Usage(merged.values.toList())
    }

    override fun equals(other: Any?): Boolean = other is Usage && components == other.components
    override fun hashCode(): Int = components.hashCode()
    override fun toString(): String = "Usage(components=$components)"
}
