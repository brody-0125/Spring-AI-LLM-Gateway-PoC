package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.PricingCatalogPort
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.PricingSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

class PostgresPricingCatalogAdapter(
    private val jdbcTemplate: JdbcTemplate,
    transactionTemplate: TransactionTemplate,
    configuredDeployments: List<Deployment>,
    private val clock: Clock = Clock.systemUTC(),
) : PricingCatalogPort {

    private val transaction = transactionTemplate
    private val configured = configuredDeployments.toList()
    private val version = pricingVersion(configured)

    fun initialize() {
        val effectiveFrom = Instant.now(clock)
        transaction.executeWithoutResult {
            jdbcTemplate.update(
                """
                INSERT INTO llm_gateway_pricing_version (version, source, effective_from)
                VALUES (?, ?, ?)
                ON CONFLICT (version) DO NOTHING
                """.trimIndent(),
                version,
                "application-config",
                Timestamp.from(effectiveFrom),
            )
            configured.forEach { deployment ->
                jdbcTemplate.update(
                    """
                    INSERT INTO llm_gateway_deployment_pricing (
                        deployment_id, pricing_version, input_cost_per_token_usd,
                        output_cost_per_token_usd, cache_read_input_cost_per_token_usd,
                        cache_write_input_cost_per_token_usd
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (deployment_id, pricing_version) DO UPDATE SET
                        input_cost_per_token_usd = EXCLUDED.input_cost_per_token_usd,
                        output_cost_per_token_usd = EXCLUDED.output_cost_per_token_usd,
                        cache_read_input_cost_per_token_usd = EXCLUDED.cache_read_input_cost_per_token_usd,
                        cache_write_input_cost_per_token_usd = EXCLUDED.cache_write_input_cost_per_token_usd
                    """.trimIndent(),
                    deployment.id.value,
                    version,
                    deployment.inputCostPer1kUsd.toPerTokenOrNull(),
                    deployment.outputCostPer1kUsd.toPerTokenOrNull(),
                    deployment.cacheReadInputCostPer1kUsd.toPerTokenOrNull(),
                    deployment.cacheWriteInputCostPer1kUsd.toPerTokenOrNull(),
                )
            }
        }
    }

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
        ).firstOrNull() ?: PricingSnapshot(
            version = "missing:${deployment.id.value}",
            inputCostPerTokenUsd = null,
            outputCostPerTokenUsd = null,
        )

    private fun BigDecimal.toPerTokenOrNull(): BigDecimal? =
        takeIf { it > BigDecimal.ZERO }
            ?.divide(BigDecimal.valueOf(1_000L), 18, RoundingMode.HALF_UP)

    private fun pricingVersion(deployments: List<Deployment>): String {
        val payload = deployments.sortedBy { it.id.value }.joinToString("|") {
            listOf(
                it.id.value,
                it.vendor.name,
                it.model,
                it.inputCostPer1kUsd.toPlainString(),
                it.outputCostPer1kUsd.toPlainString(),
                it.cacheReadInputCostPer1kUsd.toPlainString(),
                it.cacheWriteInputCostPer1kUsd.toPlainString(),
            ).joinToString(":")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "config-$digest"
    }
}
