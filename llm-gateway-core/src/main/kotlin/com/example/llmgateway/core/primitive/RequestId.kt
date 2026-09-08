package com.example.llmgateway.core.primitive

@JvmInline
value class RequestId(val value: String) {
    init {
        require(value.length in 1..128) { "request id must be between 1 and 128 characters" }
        require(value.first().isAsciiAlphaNumeric() && value.all(Char::isRequestIdCharacter)) {
            "request id contains unsupported characters"
        }
    }
}

private fun Char.isRequestIdCharacter(): Boolean =
    isAsciiAlphaNumeric() || this == '-' || this == '_' || this == '.' || this == ':'

private fun Char.isAsciiAlphaNumeric(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
