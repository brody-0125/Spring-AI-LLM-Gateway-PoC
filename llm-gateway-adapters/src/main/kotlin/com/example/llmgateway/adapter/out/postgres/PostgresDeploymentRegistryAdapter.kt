package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.DeploymentAvailabilityPort
import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.RoutingControlPlanePort
import com.example.llmgateway.application.port.out.RoutingSnapshotPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.policy.DeploymentOverride
import com.example.llmgateway.domain.routing.Deployment
import com.example.llmgateway.domain.routing.RoutingSnapshot
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

class PostgresDeploymentRegistryAdapter(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : DeploymentRegistryPort, RoutingControlPlanePort, RoutingSnapshotPort, DeploymentAvailabilityPort {

    private val writeTransaction = TransactionTemplate(transactionManager)
    private val readTransaction = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = true
    }
    override fun snapshot(): RoutingSnapshot = readTransaction.execute { readSnapshot() }

    override fun current(): RoutingSnapshot = readTransaction.execute {
        val policy = readSnapshot()
        val effectiveAt = Instant.now()
        val catalog = PostgresPricingCatalogAdapter(jdbcTemplate)
        RoutingSnapshot(
            policy.deployments,
            policy.version,
            policy.deployments.associate { it.id to catalog.resolve(it, effectiveAt) },
        )
    }

    override fun isEnabled(deploymentId: DeploymentId): Boolean = readTransaction.execute {
        jdbcTemplate.queryForList(
            "SELECT enabled FROM llm_gateway_deployment WHERE id = ?",
            Boolean::class.java,
            deploymentId.value,
        ).singleOrNull() == true
    }

    override fun update(overrides: List<DeploymentOverride>): RoutingSnapshot {
        require(overrides.isNotEmpty()) { "at least one deployment override is required" }
        require(overrides.map { it.id }.distinct().size == overrides.size) {
            "deployment overrides must not contain duplicate ids"
        }

        return writeTransaction.execute {
            jdbcTemplate.queryForObject(
                "SELECT version FROM llm_gateway_routing_version WHERE id = 1 FOR UPDATE", Long::class.java,
            ) ?: error("Routing registry has not been migrated")
            val knownIds = jdbcTemplate.queryForList(
                "SELECT id FROM llm_gateway_deployment",
                String::class.java,
            ).toSet()
            require(overrides.all { it.id.value in knownIds }) {
                "deployment override references an unknown deployment"
            }

            overrides.forEach(::applyOverride)
            jdbcTemplate.update(
                "UPDATE llm_gateway_routing_version SET version = version + 1 WHERE id = 1",
            )
            readSnapshot()
        }
    }

    private fun applyOverride(override: DeploymentOverride) {
        val assignments = buildList {
            if (override.enabled != null) add("enabled = ?")
            if (override.priority != null) add("priority = ?")
            if (override.weight != null) add("weight = ?")
        }
        if (assignments.isEmpty()) return

        val parameters = buildList<Any> {
            override.enabled?.let(::add)
            override.priority?.let(::add)
            override.weight?.let(::add)
            add(override.id.value)
        }
        jdbcTemplate.update(
            "UPDATE llm_gateway_deployment SET ${assignments.joinToString(", ")}, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            *parameters.toTypedArray(),
        )
    }

    private fun readSnapshot(): RoutingSnapshot {
        val version = jdbcTemplate.queryForObject(
            "SELECT version FROM llm_gateway_routing_version WHERE id = 1",
            Long::class.java,
        ) ?: error("routing registry version row is missing")
        val deployments = jdbcTemplate.query(
            """
            SELECT id, vendor, dialect, model_group, model, priority, enabled, weight,
                   supports_streaming, input_cost_per_1k_usd, output_cost_per_1k_usd,
                   cache_read_input_cost_per_1k_usd, cache_write_input_cost_per_1k_usd
            FROM llm_gateway_deployment
            ORDER BY id
            """.trimIndent(),
        ) { resultSet, _ ->
            Deployment(
                id = DeploymentId(resultSet.getString("id")),
                vendor = Vendor.valueOf(resultSet.getString("vendor")),
                dialect = Dialect.valueOf(resultSet.getString("dialect")),
                modelGroup = ModelGroup(resultSet.getString("model_group")),
                model = resultSet.getString("model"),
                priority = resultSet.getInt("priority"),
                enabled = resultSet.getBoolean("enabled"),
                weight = resultSet.getInt("weight"),
                supportsStreaming = resultSet.getBoolean("supports_streaming"),
                inputCostPer1kUsd = resultSet.getBigDecimal("input_cost_per_1k_usd"),
                outputCostPer1kUsd = resultSet.getBigDecimal("output_cost_per_1k_usd"),
                cacheReadInputCostPer1kUsd = resultSet.getBigDecimal("cache_read_input_cost_per_1k_usd"),
                cacheWriteInputCostPer1kUsd = resultSet.getBigDecimal("cache_write_input_cost_per_1k_usd"),
            )
        }
        return RoutingSnapshot(deployments, version)
    }
}
