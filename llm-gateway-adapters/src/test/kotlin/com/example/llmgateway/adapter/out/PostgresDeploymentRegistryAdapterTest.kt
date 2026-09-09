package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.postgres.PostgresDeploymentRegistryAdapter
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.policy.DeploymentOverride
import com.example.llmgateway.domain.routing.Deployment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource

class PostgresDeploymentRegistryAdapterTest : FunSpec() {

    init {
        test("JDBC registry persists overrides across adapter instances") {
            val dataSource = DriverManagerDataSource().apply {
                setDriverClassName("org.h2.Driver")
                url = "jdbc:h2:mem:registry-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
            }
            val jdbc = org.springframework.jdbc.core.JdbcTemplate(dataSource)
            jdbc.execute(
                """
                CREATE TABLE llm_gateway_deployment (
                    id VARCHAR(128) PRIMARY KEY,
                    vendor VARCHAR(64) NOT NULL,
                    dialect VARCHAR(64) NOT NULL,
                    model_group VARCHAR(128) NOT NULL,
                    model VARCHAR(256) NOT NULL,
                    priority INTEGER NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL,
                    weight INTEGER NOT NULL,
                    supports_streaming BOOLEAN NOT NULL,
                    input_cost_per_1k_usd DECIMAL(18, 8) NOT NULL,
                    output_cost_per_1k_usd DECIMAL(18, 8) NOT NULL,
                    cache_read_input_cost_per_1k_usd DECIMAL(18, 8) NOT NULL DEFAULT 0,
                    cache_write_input_cost_per_1k_usd DECIMAL(18, 8) NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent(),
            )
            jdbc.execute(
                """
                CREATE TABLE llm_gateway_routing_version (
                    id SMALLINT PRIMARY KEY,
                    version BIGINT NOT NULL
                )
                """.trimIndent(),
            )

            val transactionManager = DataSourceTransactionManager(dataSource)
            jdbc.update("INSERT INTO llm_gateway_routing_version (id, version) VALUES (1, 1)")
            jdbc.update(
                """
                INSERT INTO llm_gateway_deployment (
                    id, vendor, dialect, model_group, model, enabled, weight, supports_streaming,
                    input_cost_per_1k_usd, output_cost_per_1k_usd
                ) VALUES (?, 'OPENAI', 'OPENAI', 'default', 'gpt-test', true, 1, true, 0, 0)
                """.trimIndent(),
                deployment.id.value,
            )
            val first = PostgresDeploymentRegistryAdapter(jdbc, transactionManager)
            first.snapshot().version shouldBe 1L
            first.isEnabled(deployment.id) shouldBe true
            first.isEnabled(DeploymentId("absent")) shouldBe false

            first.update(listOf(DeploymentOverride(deployment.id, enabled = false, priority = 3, weight = 0)))
            val second = PostgresDeploymentRegistryAdapter(jdbc, transactionManager)
            second.snapshot().deployments.single().enabled shouldBe false
            first.isEnabled(deployment.id) shouldBe false
            second.snapshot().deployments.single().priority shouldBe 3
            second.snapshot().version shouldBe 2L
            repeat(3) {
                val configuration = com.example.llmgateway.adapter.out.springai.SpringAiVendorConfiguration()
                configuration.deploymentRegistry(jdbc, transactionManager).snapshot() shouldBe second.snapshot()
                configuration.pricingCatalog(jdbc)
            }

            shouldThrow<IllegalArgumentException> {
                second.update(listOf(DeploymentOverride(DeploymentId("unknown"), enabled = true)))
            }
            shouldThrow<IllegalArgumentException> {
                second.update(
                    listOf(
                        DeploymentOverride(deployment.id, enabled = true),
                        DeploymentOverride(deployment.id, enabled = false),
                    ),
                )
            }
        }
    }

    private val deployment = Deployment(
        id = DeploymentId("openai-jdbc-test"),
        vendor = Vendor.OPENAI,
        dialect = Dialect.OPENAI,
        modelGroup = ModelGroup("default"),
        model = "gpt-test",
    )
}
