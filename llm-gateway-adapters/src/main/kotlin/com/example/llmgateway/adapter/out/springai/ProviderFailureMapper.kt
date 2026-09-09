package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.error.ProviderException
import com.example.llmgateway.domain.error.ProviderFailurePhase
import com.example.llmgateway.domain.error.RequestDisposition
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIRetryableException
import com.openai.errors.OpenAIServiceException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
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
                phase = ProviderFailurePhase.COMPLETE,
                requestDisposition = RequestDisposition.SENT_UNKNOWN,
                retryAfter = retryAfterOf(openAiServiceException.headers().names().firstNotNullOfOrNull { name ->
                    name.takeIf { it.equals("retry-after", ignoreCase = true) }
                        ?.let { openAiServiceException.headers().values(it).firstOrNull() }
                }),
                providerRequestId = openAiServiceException.headers().names().firstNotNullOfOrNull { name ->
                    name.takeIf { it.equals("x-request-id", ignoreCase = true) }
                        ?.let { openAiServiceException.headers().values(it).firstOrNull() }
                },
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
                phase = phaseOf(causes),
                requestDisposition = dispositionOf(causes),
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
                phase = ProviderFailurePhase.COMPLETE,
                requestDisposition = RequestDisposition.SENT_UNKNOWN,
                retryAfter = awsServiceException.awsErrorDetails()
                    ?.sdkHttpResponse()
                    ?.firstMatchingHeader("retry-after")
                    ?.orElse(null)
                    ?.let(::retryAfterOf),
                providerRequestId = awsServiceException.requestId(),
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
                phase = phaseOf(causes),
                requestDisposition = dispositionOf(causes),
            )
        }

        return ProviderException(
            vendor = vendor,
            statusCode = 503,
            providerCode = "provider_error",
            message = "Provider request failed",
            cause = error,
            phase = phaseOf(causes),
            requestDisposition = dispositionOf(causes),
        )
    }

    private fun phaseOf(causes: List<Throwable>): ProviderFailurePhase = when {
        causes.any { it is ConnectException || it is UnknownHostException || it is NoRouteToHostException } ->
            ProviderFailurePhase.CONNECT
        causes.any { it is SSLException } -> ProviderFailurePhase.CONNECT
        causes.any { it is SocketTimeoutException || it is TimeoutException } -> ProviderFailurePhase.READ
        else -> ProviderFailurePhase.UNKNOWN
    }

    private fun dispositionOf(causes: List<Throwable>): RequestDisposition = when {
        causes.any { it is ConnectException || it is UnknownHostException || it is NoRouteToHostException } ->
            RequestDisposition.NOT_SENT
        else -> RequestDisposition.SENT_UNKNOWN
    }

    private fun retryAfterOf(value: String?): Duration? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        raw.toLongOrNull()?.let { seconds ->
            return runCatching { Duration.ofSeconds(seconds.coerceAtLeast(0)) }.getOrNull()
        }
        return runCatching {
            Duration.between(Instant.now(), ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .takeIf { !it.isNegative }
        }.getOrElse { error ->
            if (error is DateTimeParseException) null else throw error
        }
    }
}
