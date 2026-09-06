package com.example.llmgateway.adapter.out

import com.example.llmgateway.adapter.out.postgres.PostgresDeploymentRegistryAdapter
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.DeploymentOverride
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager

class PostgresDeploymentRegistryAdapterTest : FunSpec() {

    init {
        test("JDBC registry persists overrides across adapter instances") {
            val dataSource = DriverManagerDataSource().apply {
                setDriverClassName("org.h2.Driver")
                url = "jdbc:h2:mem:registry-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
            }
            val jdbc = H2CompatibleJdbcTemplate(dataSource)
            jdbc.execute(
                """
                CREATE TABLE llm_gateway_deployment (
                    id VARCHAR(128) PRIMARY KEY,
                    vendor VARCHAR(64) NOT NULL,
                    dialect VARCHAR(64) NOT NULL,
                    model_group VARCHAR(128) NOT NULL,
                    model VARCHAR(256) NOT NULL,
                    enabled BOOLEAN NOT NULL,
                    weight INTEGER NOT NULL,
                    supports_streaming BOOLEAN NOT NULL,
                    input_cost_per_1k_usd DECIMAL(18, 8) NOT NULL,
                    output_cost_per_1k_usd DECIMAL(18, 8) NOT NULL,
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
            val first = PostgresDeploymentRegistryAdapter(jdbc, transactionManager, listOf(deployment))
            first.initialize()
            first.snapshot().version shouldBe 1L

            first.update(listOf(DeploymentOverride(deployment.id, enabled = false, weight = 0)))
            val second = PostgresDeploymentRegistryAdapter(jdbc, transactionManager, listOf(deployment))
            second.snapshot().deployments.single().enabled shouldBe false
            second.snapshot().version shouldBe 2L

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
