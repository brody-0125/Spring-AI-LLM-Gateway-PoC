package com.example.llmgateway.adapter.out.postgres

import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.RoutingControlPlanePort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.DeploymentOverride
import com.example.llmgateway.domain.model.RoutingSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

class PostgresDeploymentRegistryAdapter(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    configuredDeployments: List<Deployment>,
) : DeploymentRegistryPort, RoutingControlPlanePort {

    private val writeTransaction = TransactionTemplate(transactionManager)
    private val readTransaction = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }
    private val configured = configuredDeployments.toList()

    init {
        require(configured.map { it.id }.distinct().size == configured.size) {
            "configured deployment ids must be unique"
        }
    }

    fun initialize() {
        writeTransaction.executeWithoutResult {
            ensureVersionRow()
            configured.forEach(::upsertConfiguredDeployment)
            disableDeploymentsNotConfigured()
        }
    }

    override fun snapshot(): RoutingSnapshot = readTransaction.execute { readSnapshot() }

    override fun update(overrides: List<DeploymentOverride>): RoutingSnapshot {
        require(overrides.isNotEmpty()) { "at least one deployment override is required" }
        require(overrides.map { it.id }.distinct().size == overrides.size) {
            "deployment overrides must not contain duplicate ids"
        }

        return writeTransaction.execute {
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

    private fun ensureVersionRow() {
        jdbcTemplate.update(
            """
            INSERT INTO llm_gateway_routing_version (id, version)
            VALUES (1, 1)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }

    private fun upsertConfiguredDeployment(deployment: Deployment) {
        jdbcTemplate.update(
            """
            INSERT INTO llm_gateway_deployment (
                id, vendor, dialect, model_group, model, priority, enabled, weight,
                supports_streaming, input_cost_per_1k_usd, output_cost_per_1k_usd,
                cache_read_input_cost_per_1k_usd, cache_write_input_cost_per_1k_usd
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                vendor = EXCLUDED.vendor,
                dialect = EXCLUDED.dialect,
                model_group = EXCLUDED.model_group,
                model = EXCLUDED.model,
                priority = EXCLUDED.priority,
                supports_streaming = EXCLUDED.supports_streaming,
                input_cost_per_1k_usd = EXCLUDED.input_cost_per_1k_usd,
                output_cost_per_1k_usd = EXCLUDED.output_cost_per_1k_usd,
                cache_read_input_cost_per_1k_usd = EXCLUDED.cache_read_input_cost_per_1k_usd,
                cache_write_input_cost_per_1k_usd = EXCLUDED.cache_write_input_cost_per_1k_usd,
                updated_at = CURRENT_TIMESTAMP
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

    private fun disableDeploymentsNotConfigured() {
        if (configured.isEmpty()) {
            jdbcTemplate.update("UPDATE llm_gateway_deployment SET enabled = FALSE")
            return
        }

        val placeholders = configured.joinToString(",") { "?" }
        jdbcTemplate.update(
            "UPDATE llm_gateway_deployment SET enabled = FALSE WHERE id NOT IN ($placeholders)",
            *configured.map { it.id.value }.toTypedArray(),
        )
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
