package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.adapter.out.pricing.toTokenPriceUsd
import com.example.llmgateway.application.port.out.RegistrySeedPort
import com.example.llmgateway.domain.policy.RegistrySeedResult
import com.example.llmgateway.domain.routing.Deployment
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** Explicit management write. Never called by normal replica startup. */
class PostgresRegistrySeedAdapter(
    private val jdbcTemplate: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) : RegistrySeedPort {
    override fun seed(deployments: List<Deployment>): RegistrySeedResult {
        require(deployments.isNotEmpty()) { "No configured deployments to seed" }
        require(deployments.map { it.id }.distinct().size == deployments.size) { "Deployment IDs must be unique" }
        return transaction.execute {
            jdbcTemplate.queryForObject(
                "SELECT version FROM llm_gateway_routing_version WHERE id = 1 FOR UPDATE", Long::class.java,
            ) ?: error("Routing registry has not been migrated")
            val created = deployments.filter { insertDeployment(it) == 1 }
            if (created.isNotEmpty()) {
                val version = pricingVersion(created)
                jdbcTemplate.update(
                    """
                    INSERT INTO llm_gateway_pricing_version (version, source, effective_from)
                    VALUES (?, ?, ?) ON CONFLICT (version) DO NOTHING
                    """.trimIndent(),
                    version, "explicit-seed", Timestamp.from(Instant.now(clock)),
                )
                created.forEach { deployment ->
                    check(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM llm_gateway_deployment_pricing WHERE deployment_id = ?",
                        Long::class.java, deployment.id.value,
                    ) == 0L) { "Existing pricing for a new deployment requires explicit reconciliation" }
                    jdbcTemplate.update(
                        """
                        INSERT INTO llm_gateway_deployment_pricing (
                            deployment_id, pricing_version, input_cost_per_token_usd, output_cost_per_token_usd,
                            cache_read_input_cost_per_token_usd, cache_write_input_cost_per_token_usd
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        deployment.id.value, version,
                        deployment.inputCostPer1kUsd.toTokenPriceUsd(),
                        deployment.outputCostPer1kUsd.toTokenPriceUsd(),
                        deployment.cacheReadInputCostPer1kUsd.toTokenPriceUsd(),
                        deployment.cacheWriteInputCostPer1kUsd.toTokenPriceUsd(),
                    )
                }
                jdbcTemplate.update("UPDATE llm_gateway_routing_version SET version = version + 1 WHERE id = 1")
            }
            RegistrySeedResult(created.size, deployments.size - created.size)
        }
    }

    private fun insertDeployment(deployment: Deployment): Int {
        return jdbcTemplate.update(
            """
            INSERT INTO llm_gateway_deployment (
                id, vendor, dialect, model_group, model, priority, enabled, weight,
                supports_streaming, input_cost_per_1k_usd, output_cost_per_1k_usd,
                cache_read_input_cost_per_1k_usd, cache_write_input_cost_per_1k_usd
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            deployment.id.value,
            deployment.vendor.name,
            deployment.dialect.name,
            deployment.modelGroup.value,
            deployment.model,
            deployment.priority,
            deployment.enabled,
            deployment.weight,
            deployment.supportsStreaming,
            deployment.inputCostPer1kUsd,
            deployment.outputCostPer1kUsd,
            deployment.cacheReadInputCostPer1kUsd,
            deployment.cacheWriteInputCostPer1kUsd,
        )
    }

    private fun pricingVersion(deployments: List<Deployment>): String {
        val payload = deployments.sortedBy { it.id.value }.joinToString("|") {
            listOf(
                it.id.value,
                it.vendor.name,
                it.model,
                it.inputCostPer1kUsd?.toPlainString() ?: "unknown",
                it.outputCostPer1kUsd?.toPlainString() ?: "unknown",
                it.cacheReadInputCostPer1kUsd?.toPlainString() ?: "unknown",
                it.cacheWriteInputCostPer1kUsd?.toPlainString() ?: "unknown",
            ).joinToString(":")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "seed-$digest"
    }
}
