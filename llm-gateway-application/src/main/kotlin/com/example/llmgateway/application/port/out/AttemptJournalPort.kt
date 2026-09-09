package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.routing.CircuitPermit

interface AttemptJournalPort : AttemptAccountingPort {
    /** Atomically reserve the owned budget and prepare intent before obtaining a permit. */
    fun prepare(context: AttemptContext, pricing: PricingSnapshot?)
    /** A single PREPARED-to-intent transition; a repeated dispatch must be rejected. */
    fun dispatch(context: AttemptContext, permit: CircuitPermit)
    /** Only a PREPARED attempt is known not to have reached the provider boundary. */
    fun abandon(context: AttemptContext)
}
