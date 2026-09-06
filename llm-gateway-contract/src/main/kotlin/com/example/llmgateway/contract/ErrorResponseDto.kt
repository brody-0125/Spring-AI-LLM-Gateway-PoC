package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonProperty

data class ErrorResponseDto(
    @param:JsonProperty("error") val error: GatewayErrorDto,
)
