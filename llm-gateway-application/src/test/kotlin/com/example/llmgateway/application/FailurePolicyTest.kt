package com.example.llmgateway.application

import com.example.llmgateway.application.policy.DefaultFailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.ProviderException
import com.example.llmgateway.core.primitive.Vendor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.SocketTimeoutException
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

class FailurePolicyTest : FunSpec({
    val policy = FailurePolicy()

    test("fallback, circuit breaker, and client retry policies are explicit") {
        policy.fallbackEligible(FailureClass.TRANSIENT) shouldBe true
        policy.fallbackEligible(FailureClass.RATE_LIMITED) shouldBe true
        policy.fallbackEligible(FailureClass.CONTEXT_WINDOW) shouldBe true
        policy.fallbackEligible(FailureClass.AUTHENTICATION) shouldBe false
        policy.circuitBreakerEligible(FailureClass.CONTEXT_WINDOW) shouldBe false
        policy.clientRetryable(FailureClass.CONTEXT_WINDOW) shouldBe false
        policy.clientRetryable(FailureClass.RATE_LIMITED) shouldBe true
    }

    test("failure classifier maps provider status and wrapped timeout errors") {
        val classifier = DefaultFailureClassifier()

        classifier.classify(ProviderException(Vendor.OPENAI, 401, message = "invalid key")) shouldBe
            FailureClass.AUTHENTICATION
        classifier.classify(ProviderException(Vendor.OPENAI, 429, message = "rate limit")) shouldBe
            FailureClass.RATE_LIMITED
        classifier.classify(ProviderException(Vendor.OPENAI, 400, providerCode = "context_length_exceeded", message = "context")) shouldBe
            FailureClass.CONTEXT_WINDOW
        classifier.classify(ProviderException(Vendor.OPENAI, 400, message = "invalid parameter")) shouldBe
            FailureClass.INVALID_REQUEST
        classifier.classify(CompletionException(TimeoutException("timed out"))) shouldBe FailureClass.TRANSIENT
        classifier.classify(SocketTimeoutException("timed out")) shouldBe FailureClass.TRANSIENT
    }
})
