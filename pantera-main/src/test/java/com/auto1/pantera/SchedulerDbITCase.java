/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.DbConsumer;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.LocalEventDrainScheduler;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Test for {@link LocalEventDrainScheduler} and
 * {@link com.auto1.pantera.db.DbConsumer} together — the full node-local
 * "queue -&gt; drain -&gt; DbConsumer -&gt; Postgres" pipeline (WS2.2, 2.3.0).
 * No longer goes through {@link com.auto1.pantera.scheduling.QuartzService}:
 * that Quartz-based drain was the pipeline WS2.2 replaced.
 *
 * <p>Runs as an ITCase ({@code mvn verify -Pitcase}), not part of the
 * default {@code mvn test} run: it exercises the real JDBC path end-to-end
 * (1000 rows through {@link DbConsumer} into a genuine Postgres instance),
 * so it needs Docker and is materially slower than the surefire unit
 * budget. It was previously a plain {@code *Test.java} (surefire/unit
 * phase) starting its own TestContainers Postgres — that both required
 * Docker in the unit phase and added multi-JVM container contention under
 * {@code mvn -T8}, which is the class of flake this move eliminates. The
 * assertions are unchanged from the original unit test, only relocated to
 * the correct phase.</p>
 *
 * @since 0.31
 */
@Testcontainers
final class SchedulerDbITCase {

    /**
     * PostgreSQL test container.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    /**
     * Test connection.
     */
    private DataSource source;

    /**
     * Scheduler under test.
     */
    private LocalEventDrainScheduler<ArtifactEvent> drain;

    @BeforeEach
    void init() {
        this.source = new ArtifactDbFactory(
            Yaml.createYamlMappingBuilder().add(
                "artifacts_database",
                Yaml.createYamlMappingBuilder()
                    .add(ArtifactDbFactory.YAML_HOST, POSTGRES.getHost())
                    .add(ArtifactDbFactory.YAML_PORT, String.valueOf(POSTGRES.getFirstMappedPort()))
                    .add(ArtifactDbFactory.YAML_DATABASE, POSTGRES.getDatabaseName())
                    .add(ArtifactDbFactory.YAML_USER, POSTGRES.getUsername())
                    .add(ArtifactDbFactory.YAML_PASSWORD, POSTGRES.getPassword())
                    .build()
            ).build(),
            "artifacts"
        ).initialize();
    }

    @AfterEach
    void stop() {
        if (this.drain != null) {
            this.drain.close();
        }
    }

    @Test
    void insertsRecords() {
        final Queue<ArtifactEvent> queue = new ConcurrentLinkedDeque<>();
        this.drain = new LocalEventDrainScheduler<>(
            queue, List.of(new DbConsumer(this.source), new DbConsumer(this.source)), 1
        );
        final long created = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            queue.add(
                new ArtifactEvent(
                    "rpm", "my-rpm", "Alice", "org.time", String.valueOf(i), 1250L, created
                )
            );
        }
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(
            () -> {
                try (
                    Connection conn = this.source.getConnection();
                    Statement stat = conn.createStatement()
                ) {
                    stat.execute("SELECT COUNT(*) FROM artifacts");
                    final ResultSet rs = stat.getResultSet();
                    rs.next();
                    return rs.getInt(1) == 1000;
                }
            }
        );
    }

}
