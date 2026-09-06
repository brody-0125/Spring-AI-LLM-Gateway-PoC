package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.ProviderException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIRetryableException
import com.openai.errors.OpenAIServiceException
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkClientException

internal object ProviderFailureMapper {
    fun map(error: Throwable, vendor: Vendor): ProviderException {
        val causes = generateSequence(error) { it.cause }.toList()
        val providerException = causes.firstOrNull { it is ProviderException }
        if (providerException is ProviderException) return providerException

        val openAiServiceException = causes.firstOrNull { it is OpenAIServiceException } as? OpenAIServiceException
        if (openAiServiceException != null) {
            return ProviderException(
                vendor = vendor,
                statusCode = openAiServiceException.statusCode(),
                providerCode = openAiServiceException.code().orElse(null) ?: openAiServiceException.type().orElse(null),
                message = openAiServiceException.message ?: "Provider request failed",
                cause = error,
            )
        }

        val retryableOpenAiException = causes.firstOrNull {
            it is OpenAIRetryableException || it is OpenAIIoException
        }
        if (retryableOpenAiException != null) {
            return ProviderException(
                vendor = vendor,
                statusCode = 503,
                providerCode = "retryable_provider_error",
                message = retryableOpenAiException.message ?: "Provider request failed",
                cause = error,
            )
        }

        val awsServiceException = causes.firstOrNull { it is AwsServiceException } as? AwsServiceException
        if (awsServiceException != null) {
            return ProviderException(
                vendor = vendor,
                statusCode = awsServiceException.statusCode(),
                providerCode = awsServiceException.awsErrorDetails()?.errorCode(),
                message = awsServiceException.message ?: "Provider request failed",
                cause = error,
            )
        }

        val sdkClientException = causes.firstOrNull { it is SdkClientException }
        if (sdkClientException != null) {
            return ProviderException(
                vendor = vendor,
                statusCode = 503,
                providerCode = "provider_client_error",
                message = sdkClientException.message ?: "Provider client failed",
                cause = error,
            )
        }

        return ProviderException(
            vendor = vendor,
            statusCode = 503,
            providerCode = "provider_error",
            message = "Provider request failed",
            cause = error,
        )
    }
}
