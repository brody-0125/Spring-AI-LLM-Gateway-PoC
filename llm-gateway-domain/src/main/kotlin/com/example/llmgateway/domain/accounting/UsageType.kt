package com.example.llmgateway.domain.accounting

enum class UsageType(val unit: UsageUnit) {
    INPUT_TOKENS(UsageUnit.TOKEN),
    OUTPUT_TOKENS(UsageUnit.TOKEN),
    CACHE_READ_INPUT_TOKENS(UsageUnit.TOKEN),
    CACHE_WRITE_INPUT_TOKENS(UsageUnit.TOKEN),
    REASONING_OUTPUT_TOKENS(UsageUnit.TOKEN),
    RERANK_QUERIES(UsageUnit.QUERY),
    RERANK_DOCUMENT_BLOCKS(UsageUnit.DOCUMENT_BLOCK),
    TOOL_INVOCATIONS(UsageUnit.INVOCATION),
}
