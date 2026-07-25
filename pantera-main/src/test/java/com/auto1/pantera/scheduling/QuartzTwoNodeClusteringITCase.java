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
package com.auto1.pantera.scheduling;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Two real {@link QuartzService} instances (two independent {@code Scheduler}
 * objects — Quartz distinguishes cluster members by scheduler instance, not
 * by JVM, so this faithfully reproduces cross-node trigger acquisition)
 * sharing one JDBC job store, proving the WS2.2 (2.3.0) fix: a node whose
 * scheduler thread acquires and fires a trigger it cannot resolve
 * dependencies for must skip that execution, not delete the shared
 * job/trigger out from under whichever node <em>can</em> resolve them.
 * <p>
 * This is the crux scenario cited in the WS2 spec and cannot be faithfully
 * reproduced as a fast unit test — clustered trigger acquisition depends on
 * genuine JDBC row-locking semantics in {@code JobStoreTX}, so it runs as an
 * ITCase ({@code mvn verify -Pitcase}), not part of the default {@code mvn
 * test} run.
 * <p>
 * The artifact-events pipeline itself (audit records / search-index rows)
 * no longer goes through Quartz at all — see {@link LocalEventDrainScheduler}
 * — so for that pipeline specifically the "no events lost" guarantee is
 * structural (there is no cross-node trigger race to lose events to any
 * more), covered by {@link LocalEventDrainSchedulerTest} and
 * {@code SchedulerDbTest}. This ITCase covers the shared safety net
 * ({@link QuartzJob#stopJob}) that still protects the remaining
 * Quartz-scheduled, {@link JobDataRegistry}-dependent per-repository
 * proxy-package-processor jobs.
 *
 * @since 2.3.0
 */
@Testcontainers
final class QuartzTwoNodeClusteringITCase {

    /**
     * PostgreSQL test container — the one shared JDBC job store both
     * {@link QuartzService} instances point at.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    /**
     * Shared DataSource both "nodes" use.
     */
    private DataSource source;

    /**
     * "Node A".
     */
    private QuartzService nodeA;

    /**
     * "Node B" — shares {@link #source}, i.e. the same QRTZ_* tables.
     */
    private QuartzService nodeB;

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
        this.nodeA = new QuartzService(this.source);
        this.nodeB = new QuartzService(this.source);
    }

    @AfterEach
    void tearDown() {
        if (this.nodeB != null) {
            this.nodeB.stop();
        }
        if (this.nodeA != null) {
            this.nodeA.stop();
        }
    }

    @Test
    @Timeout(60)
    void wrongNodeFiringDoesNotDeleteTheSharedJob() throws Exception {
        UnresolvedDependencyJob.HITS.set(0);
        // Deliberately empty JobDataMap: every firing — by whichever
        // scheduler ("node") Quartz's JDBC locking hands the trigger to —
        // finds nothing to resolve and calls stopJob(). Pre-2.3.0 this
        // deleted the job after its very first firing.
        final Set<JobKey> keys = this.nodeA.schedulePeriodicJob(
            1, 1, UnresolvedDependencyJob.class, new JobDataMap()
        );
        final JobKey key = keys.iterator().next();
        this.nodeA.start();
        this.nodeB.start();
        // Let the trigger fire repeatedly, racing between both scheduler
        // instances against the one shared JDBC store.
        Awaitility.await().atMost(30, TimeUnit.SECONDS)
            .until(() -> UnresolvedDependencyJob.HITS.get() >= 5);
        MatcherAssert.assertThat(
            "Job must survive repeated unresolved firings under real JDBC "
                + "clustering — stopJob() must not self-destruct the shared "
                + "job/trigger (WS2.2)",
            this.jobDetailCount(key),
            Matchers.is(1)
        );
        // The trigger must also still be live — not just the job row —
        // otherwise the job would silently stop firing forever without
        // ever having been deleted, which is an equally silent data-loss
        // shape.
        MatcherAssert.assertThat(
            "Trigger must still be scheduled after surviving unresolved firings",
            this.triggerCount(key),
            Matchers.is(1)
        );
    }

    private int jobDetailCount(final JobKey key) throws Exception {
        return this.count(
            "SELECT COUNT(*) FROM QRTZ_JOB_DETAILS WHERE JOB_NAME = ? AND JOB_GROUP = ?",
            key
        );
    }

    private int triggerCount(final JobKey key) throws Exception {
        return this.count(
            "SELECT COUNT(*) FROM QRTZ_TRIGGERS WHERE JOB_NAME = ? AND JOB_GROUP = ?",
            key
        );
    }

    private int count(final String sql, final JobKey key) throws Exception {
        try (Connection conn = this.source.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key.getName());
            ps.setString(2, key.getGroup());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Job whose dependency never resolves (the scheduling {@link JobDataMap}
     * is deliberately left empty) — reproduces the pre-2.3.0 self-destruct
     * condition on every single firing.
     * @since 2.3.0
     */
    public static final class UnresolvedDependencyJob extends QuartzJob {

        /**
         * Total executions across both "nodes".
         */
        static final AtomicInteger HITS = new AtomicInteger();

        @Override
        public void execute(final JobExecutionContext context) {
            UnresolvedDependencyJob.HITS.incrementAndGet();
            super.stopJob(context);
        }
    }
}
