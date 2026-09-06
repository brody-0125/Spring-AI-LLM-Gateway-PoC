package com.example.llmgateway.adapter.`in`.web

import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class OpenApiController {
    @GetMapping("/v3/api-docs.yaml", produces = ["application/yaml"])
    fun openApi(): Resource = ClassPathResource("openapi.yaml")
}
