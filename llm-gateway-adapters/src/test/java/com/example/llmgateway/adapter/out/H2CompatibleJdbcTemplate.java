package com.example.llmgateway.adapter.out;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

final class H2CompatibleJdbcTemplate extends JdbcTemplate {
    H2CompatibleJdbcTemplate(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public int update(String sql) {
        if (sql.contains("ON CONFLICT (id) DO NOTHING")) {
            return super.update("MERGE INTO llm_gateway_routing_version KEY(id) VALUES (1, 1)");
        }
        return super.update(sql);
    }

    @Override
    public int update(String sql, Object... args) {
        if (sql.contains("INSERT INTO llm_gateway_deployment")) {
            return super.update(
                "MERGE INTO llm_gateway_deployment KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                args
            );
        }
        return super.update(sql, args);
    }
}
