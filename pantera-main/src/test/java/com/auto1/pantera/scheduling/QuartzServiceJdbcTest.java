/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.scheduling;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.cactoos.list.ListOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link QuartzService} in JDBC clustering mode.
 * Uses Testcontainers PostgreSQL.
 *
 * @since 1.20.13
 */
@Testcontainers
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class QuartzServiceJdbcTest {

    /**
     * PostgreSQL test container.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    /**
     * Shared DataSource.
     */
    private DataSource source;

    /**
     * Service under test.
     */
    private QuartzService service;

    @BeforeEach
    void setUp() {
        this.source = new ArtifactDbFactory(
            Yaml.createYamlMappingBuilder().add(
                "artifacts_database",
                Yaml.createYamlMappingBuilder()
                    .add(ArtifactDbFactory.YAML_HOST, POSTGRES.getHost())
                    .add(
                        ArtifactDbFactory.YAML_PORT,
                        String.valueOf(POSTGRES.getFirstMappedPort())
                    )
                    .add(ArtifactDbFactory.YAML_DATABASE, POSTGRES.getDatabaseName())
                    .add(ArtifactDbFactory.YAML_USER, POSTGRES.getUsername())
                    .add(ArtifactDbFactory.YAML_PASSWORD, POSTGRES.getPassword())
                    .build()
            ).build(),
            "artifacts"
        ).initialize();
        this.service = new QuartzService(this.source);
    }

    @AfterEach
    void tearDown() {
        if (this.service != null) {
            this.service.stop();
        }
    }

    @Test
    void createsQuartzSchemaTablesOnStartup() throws Exception {
        try (Connection conn = this.source.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rset = stmt.executeQuery(
                "SELECT tablename FROM pg_tables WHERE tablename LIKE 'qrtz_%' ORDER BY tablename"
            )) {
            final java.util.List<String> tables = new java.util.ArrayList<>();
            while (rset.next()) {
                tables.add(rset.getString(1));
            }
            MatcherAssert.assertThat(
                "QRTZ tables should be created",
                tables,
                Matchers.hasItems(
                    "qrtz_job_details",
                    "qrtz_triggers",
                    "qrtz_simple_triggers",
                    "qrtz_cron_triggers",
                    "qrtz_fired_triggers",
                    "qrtz_locks",
                    "qrtz_scheduler_state",
                    "qrtz_calendars",
                    "qrtz_paused_trigger_grps"
                )
            );
        }
    }

    @Test
    void isClusteredModeEnabled() {
        MatcherAssert.assertThat(
            "JDBC constructor should enable clustered mode",
            this.service.isClustered(),
            Matchers.is(true)
        );
    }

    @Test
    void ramModeIsNotClustered() {
        final QuartzService ram = new QuartzService();
        try {
            MatcherAssert.assertThat(
                "RAM constructor should not enable clustered mode",
                ram.isClustered(),
                Matchers.is(false)
            );
        } finally {
            ram.stop();
        }
    }

    @Test
    void schedulesAndExecutesPeriodicJob() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        final Queue<String> queue = this.service.addPeriodicEventsProcessor(
            1,
            new ListOf<Consumer<String>>(item -> count.incrementAndGet())
        );
        this.service.start();
        queue.add("one");
        queue.add("two");
        queue.add("three");
        Awaitility.await().atMost(15, TimeUnit.SECONDS)
            .until(() -> count.get() >= 3);
        MatcherAssert.assertThat(
            "All 3 items should be processed by JDBC-backed scheduler",
            count.get(),
            Matchers.greaterThanOrEqualTo(3)
        );
    }

    @Test
    void registersSchedulerStateInDatabase() throws Exception {
        this.service.start();
        // Allow scheduler to register with the DB
        Thread.sleep(2000);
        try (Connection conn = this.source.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rset = stmt.executeQuery(
                "SELECT COUNT(*) FROM QRTZ_SCHEDULER_STATE"
            )) {
            rset.next();
            MatcherAssert.assertThat(
                "Scheduler should register its state in the database",
                rset.getInt(1),
                Matchers.greaterThanOrEqualTo(1)
            );
        }
    }

    @Test
    void doubleStopDoesNotThrowInJdbcMode() {
        this.service.start();
        this.service.stop();
        this.service.stop();
        // If we get here without exception, the test passes
        this.service = null;
    }
}
