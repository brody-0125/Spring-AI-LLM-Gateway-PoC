package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.PricingCatalogPort
import com.example.llmgateway.domain.accounting.ComponentPrice
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.accounting.UsageKey
import com.example.llmgateway.domain.accounting.UsageType
import com.example.llmgateway.domain.routing.Deployment
import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate

class PostgresPricingCatalogAdapter(
    private val jdbcTemplate: JdbcTemplate,
) : PricingCatalogPort {

    override fun resolve(deployment: Deployment, at: Instant): PricingSnapshot =
        jdbcTemplate.query(
            """
            SELECT p.pricing_version, p.input_cost_per_token_usd,
                   p.output_cost_per_token_usd, p.cache_read_input_cost_per_token_usd,
                   p.cache_write_input_cost_per_token_usd, v.effective_from
            FROM llm_gateway_deployment_pricing p
            JOIN llm_gateway_pricing_version v ON v.version = p.pricing_version
            WHERE p.deployment_id = ? AND v.effective_from <= ?
            ORDER BY v.effective_from DESC
            LIMIT 1
            """.trimIndent(),
            { resultSet, _ ->
                PricingSnapshot(
                    version = resultSet.getString("pricing_version"),
                    inputCostPerTokenUsd = resultSet.getBigDecimal("input_cost_per_token_usd"),
                    outputCostPerTokenUsd = resultSet.getBigDecimal("output_cost_per_token_usd"),
                    cacheReadInputCostPerTokenUsd = resultSet.getBigDecimal("cache_read_input_cost_per_token_usd"),
                    cacheWriteInputCostPerTokenUsd = resultSet.getBigDecimal("cache_write_input_cost_per_token_usd"),
                    effectiveFrom = resultSet.getTimestamp("effective_from").toInstant(),
                )
            },
            deployment.id.value,
            Timestamp.from(at),
        ).firstOrNull()?.let { base ->
            val additional = jdbcTemplate.query(
                """SELECT usage_type, variant, unit, usd_per_unit
                   FROM llm_gateway_component_pricing WHERE deployment_id = ? AND pricing_version = ?
                   ORDER BY usage_type, variant""",
                { row, _ ->
                    val key = UsageKey(UsageType.valueOf(row.getString("usage_type")), row.getString("variant"))
                    check(key.type.unit.name == row.getString("unit")) { "Stored price unit does not match its type" }
                    ComponentPrice(key, row.getBigDecimal("usd_per_unit"))
                }, deployment.id.value, base.version,
            )
            PricingSnapshot(base.version, base.prices + additional, base.effectiveFrom)
        } ?: PricingSnapshot(
            version = "missing:${deployment.id.value}",
            inputCostPerTokenUsd = null,
            outputCostPerTokenUsd = null,
        )

}
