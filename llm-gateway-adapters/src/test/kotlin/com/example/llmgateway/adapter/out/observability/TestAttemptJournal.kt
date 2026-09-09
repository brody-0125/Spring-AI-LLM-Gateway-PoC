package com.example.llmgateway.adapter.out.observability

import com.example.llmgateway.application.port.out.AttemptAccountingPort
import com.example.llmgateway.application.port.out.AttemptJournalPort
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.routing.CircuitPermit

/** In-process fixture only; never supplied by production configuration. */
internal class TestAttemptJournal(private val writer: AttemptAccountingPort? = null) : AttemptJournalPort {
    val states = linkedMapOf<String, String>()
    val events = mutableListOf<String>()
    var failureAt: String? = null

    private fun enter(action: String) {
        events += action
        check(failureAt != action) { "Injected journal $action failure" }
    }

    @Synchronized
    override fun prepare(context: AttemptContext, pricing: PricingSnapshot?) {
        enter("prepare")
        check(states.putIfAbsent(context.attemptId.value, "PREPARED") == null)
    }

    @Synchronized
    override fun dispatch(context: AttemptContext, permit: CircuitPermit) {
        enter("dispatch")
        check(permit.owner == context.attemptId)
        check(states.replace(context.attemptId.value, "PREPARED", "DISPATCH_INTENT"))
    }

    @Synchronized
    override fun abandon(context: AttemptContext) {
        enter("abandon")
        check(states.replace(context.attemptId.value, "PREPARED", "ABANDONED"))
    }

    @Synchronized
    override fun record(context: AttemptContext, outcome: AttemptOutcome) {
        enter("record")
        check(states[context.attemptId.value] == "DISPATCH_INTENT")
        writer?.record(context, outcome)
        states[context.attemptId.value] = "RECORDED"
    }
}
