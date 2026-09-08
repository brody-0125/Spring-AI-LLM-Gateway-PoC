package com.example.llmgateway.application

import com.example.llmgateway.application.operator.DefaultOutputGuardrailOperator
import com.example.llmgateway.application.port.out.OutputGuardrailPort
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.GuardrailDecision
import com.example.llmgateway.domain.model.RequestContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OutputGuardrailOperatorTest : FunSpec({
    val context = RequestContext(RequestId("request-output-guardrail"))
    val errors = GatewayErrorFactory(FailurePolicy())

    test("complete output rejection uses a stable client-safe error") {
        val operator = DefaultOutputGuardrailOperator(
            guardrail = OutputGuardrailPort { _, _ ->
                GuardrailDecision(false, "provider-specific detail", "OUTPUT_POLICY_BLOCKED")
            },
            errorFactory = errors,
        )

        val error = shouldThrow<GatewayException> {
            operator.inspectComplete("blocked output", context)
        }

        error.error.type shouldBe "response_guardrail_rejected"
        error.error.code shouldBe "OUTPUT_POLICY_BLOCKED"
        error.error.message shouldBe "The model response was rejected by a gateway policy"
    }

    test("stream inspection catches a blocked phrase across chunk boundaries") {
        val operator = DefaultOutputGuardrailOperator(
            guardrail = OutputGuardrailPort { output, _ ->
                if (output.contains("blocked")) {
                    GuardrailDecision(false, code = "OUTPUT_POLICY_BLOCKED")
                } else {
                    GuardrailDecision.ALLOWED
                }
            },
            errorFactory = errors,
            streamInspectionWindowCharacters = 32,
        )
        val session = operator.openStream(context)

        session.inspect("prefix bloc")
        val error = shouldThrow<GatewayException> {
            session.inspect("ked suffix")
        }

        error.error.code shouldBe "OUTPUT_POLICY_BLOCKED"
    }

    test("stream inspection enforces a bounded output size") {
        val operator = DefaultOutputGuardrailOperator(
            guardrail = OutputGuardrailPort { _, _ -> GuardrailDecision.ALLOWED },
            errorFactory = errors,
            maxStreamOutputCharacters = 4,
        )

        val error = shouldThrow<GatewayException> {
            operator.openStream(context).inspect("12345")
        }

        error.error.code shouldBe "OUTPUT_TOO_LARGE"
    }

    test("guardrail backend failures are surfaced as retryable gateway errors") {
        val operator = DefaultOutputGuardrailOperator(
            guardrail = OutputGuardrailPort { _, _ -> error("policy backend unavailable") },
            errorFactory = errors,
        )

        val error = shouldThrow<GatewayException> {
            operator.inspectComplete("output", context)
        }

        error.error.code shouldBe "GUARDRAIL_UNAVAILABLE"
        error.error.retryable shouldBe true
    }
})
