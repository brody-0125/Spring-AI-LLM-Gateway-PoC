package com.example.llmgateway.adapter.out;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Pauses after the first real version SELECT; never rewrites SQL or substitutes results. */
public final class SnapshotBarrierJdbcTemplate extends JdbcTemplate {
    private final CountDownLatch versionRead;
    private final CountDownLatch published;
    private final AtomicBoolean armed = new AtomicBoolean(true);

    public SnapshotBarrierJdbcTemplate(DataSource source, CountDownLatch versionRead, CountDownLatch published) {
        super(source);
        this.versionRead = versionRead;
        this.published = published;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType) {
        T result = super.queryForObject(sql, requiredType);
        if (sql.equals("SELECT version FROM llm_gateway_routing_version WHERE id = 1") && armed.getAndSet(false)) {
            versionRead.countDown();
            try {
                if (!published.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Publish did not finish");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Snapshot test interrupted", error);
            }
        }
        return result;
    }
}
