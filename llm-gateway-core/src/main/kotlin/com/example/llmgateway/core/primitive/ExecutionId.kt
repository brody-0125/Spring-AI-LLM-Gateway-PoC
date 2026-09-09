package com.example.llmgateway.core.primitive

import java.util.UUID

@JvmInline
value class ExecutionId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128) { "Invalid execution ID" }
    }

    companion object {
        fun newId(): ExecutionId = ExecutionId("exec_${UUID.randomUUID()}")
    }
}
