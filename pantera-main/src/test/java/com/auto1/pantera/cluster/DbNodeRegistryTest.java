/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cluster;

import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link DbNodeRegistry}.
 *
 * @since 1.20.13
 */
@SuppressWarnings("PMD.TooManyMethods")
@Testcontainers
class DbNodeRegistryTest {

    /**
     * PostgreSQL test container.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    /**
     * Data source for tests.
     */
    private DataSource source;

    /**
     * Registry under test.
     */
    private DbNodeRegistry registry;

    @BeforeEach
    void setUp() throws SQLException {
        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(5);
        config.setPoolName("DbNodeRegistryTest-Pool");
        this.source = new HikariDataSource(config);
        // Drop and recreate for clean state
        try (Connection conn = this.source.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP TABLE IF EXISTS pantera_nodes");
        }
        this.registry = new DbNodeRegistry(this.source);
        this.registry.createTable();
    }

    @Test
    void createsTableWithoutError() throws SQLException {
        // Table already created in setUp; calling again should be idempotent
        this.registry.createTable();
    }

    @Test
    void registersNode() throws SQLException {
        final NodeRegistry.NodeInfo node = new NodeRegistry.NodeInfo(
            "node-1", "host-1", Instant.now(), Instant.now()
        );
        this.registry.register(node);
        final List<NodeRegistry.NodeInfo> live = this.registry.liveNodes(30_000L);
        MatcherAssert.assertThat(live, Matchers.hasSize(1));
        MatcherAssert.assertThat(
            live.get(0).nodeId(),
            new IsEqual<>("node-1")
        );
        MatcherAssert.assertThat(
            live.get(0).hostname(),
            new IsEqual<>("host-1")
        );
    }

    @Test
    void upsertUpdatesExistingNode() throws SQLException {
        final Instant started = Instant.now();
        this.registry.register(
            new NodeRegistry.NodeInfo("node-1", "host-1", started, started)
        );
        this.registry.register(
            new NodeRegistry.NodeInfo("node-1", "host-updated", started, started)
        );
        final List<NodeRegistry.NodeInfo> live = this.registry.liveNodes(30_000L);
        MatcherAssert.assertThat(live, Matchers.hasSize(1));
        MatcherAssert.assertThat(
            live.get(0).hostname(),
            new IsEqual<>("host-updated")
        );
    }

    @Test
    void registersMultipleNodes() throws SQLException {
        final Instant now = Instant.now();
        this.registry.register(
            new NodeRegistry.NodeInfo("node-1", "host-1", now, now)
        );
        this.registry.register(
            new NodeRegistry.NodeInfo("node-2", "host-2", now, now)
        );
        this.registry.register(
            new NodeRegistry.NodeInfo("node-3", "host-3", now, now)
        );
        final List<NodeRegistry.NodeInfo> live = this.registry.liveNodes(30_000L);
        MatcherAssert.assertThat(live, Matchers.hasSize(3));
    }

    @Test
    void heartbeatUpdatesTimestamp() throws SQLException {
        final Instant now = Instant.now();
        this.registry.register(
            new NodeRegistry.NodeInfo("node-1", "host-1", now, now)
        );
        this.registry.heartbeat("node-1");
        final List<NodeRegistry.NodeInfo> live = this.registry.liveNodes(30_000L);
        MatcherAssert.assertThat(live, Matchers.hasSize(1));
    }

    @Test
    void deregisterSetsStatusToStopped() throws SQLException {
        final Instant now = Instant.now();
        this.registry.register(
            new NodeRegistry.NodeInfo("node-1", "host-1", now, now)
        );
        this.registry.deregister("node-1");
        final List<NodeRegistry.NodeInfo> live = this.registry.liveNodes(30_000L);
        MatcherAssert.assertThat(
            "Deregistered node should not appear in live nodes",
            live, Matchers.hasSize(0)
        );
    }

    @Test
    void liveNodesExcludesStaleNodes() throws SQLException {
        final Instant now = Instant.now();
        // Register two nodes
        this.registry.register(
            new NodeRegistry.NodeInfo("node-fresh", "host-1", now, now)
        );
        this.registry.register(
            new NodeRegistry.NodeInfo("node-stale", "host-2", now, now)
        );
        // Manually set one node's heartbeat to far in the past
        try (Connection conn = this.source.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "UPDATE pantera_nodes SET last_heartbeat = TIMESTAMP '2020-01-01 00:00:00'"
                    + " WHERE node_id = 'node-stale'"
            );
        }
        // Only the fresh node should appear with a 30s timeout
        final List<NodeRegistry.NodeInfo> live = this.registry.liveNodes(30_000L);
        MatcherAssert.assertThat(live, Matchers.hasSize(1));
        MatcherAssert.assertThat(
            live.get(0).nodeId(),
            new IsEqual<>("node-fresh")
        );
    }

    @Test
    void evictStaleRemovesOldNodes() throws SQLException {
        final Instant now = Instant.now();
        this.registry.register(
            new NodeRegistry.NodeInfo("node-fresh", "host-1", now, now)
        );
        this.registry.register(
            new NodeRegistry.NodeInfo("node-stale", "host-2", now, now)
        );
        // Manually set one node's heartbeat to far in the past
        try (Connection conn = this.source.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "UPDATE pantera_nodes SET last_heartbeat = TIMESTAMP '2020-01-01 00:00:00'"
                    + " WHERE node_id = 'node-stale'"
            );
        }
        final int evicted = this.registry.evictStale(30_000L);
        MatcherAssert.assertThat(evicted, new IsEqual<>(1));
        // Only the fresh node should remain
        final List<NodeRegistry.NodeInfo> live = this.registry.liveNodes(30_000L);
        MatcherAssert.assertThat(live, Matchers.hasSize(1));
        MatcherAssert.assertThat(
            live.get(0).nodeId(),
            new IsEqual<>("node-fresh")
        );
    }

    @Test
    void evictStaleReturnsZeroWhenNothingToEvict() throws SQLException {
        final Instant now = Instant.now();
        this.registry.register(
            new NodeRegistry.NodeInfo("node-1", "host-1", now, now)
        );
        final int evicted = this.registry.evictStale(30_000L);
        MatcherAssert.assertThat(evicted, new IsEqual<>(0));
    }

    @Test
    void heartbeatForUnknownNodeDoesNotFail() throws SQLException {
        // Should not throw; logs a warning
        this.registry.heartbeat("nonexistent-node");
    }

    @Test
    void reRegisterAfterDeregister() throws SQLException {
        final Instant now = Instant.now();
        this.registry.register(
            new NodeRegistry.NodeInfo("node-1", "host-1", now, now)
        );
        this.registry.deregister("node-1");
        MatcherAssert.assertThat(
            this.registry.liveNodes(30_000L), Matchers.hasSize(0)
        );
        // Re-register should bring the node back
        this.registry.register(
            new NodeRegistry.NodeInfo("node-1", "host-1", now, now)
        );
        final List<NodeRegistry.NodeInfo> live = this.registry.liveNodes(30_000L);
        MatcherAssert.assertThat(live, Matchers.hasSize(1));
        MatcherAssert.assertThat(
            live.get(0).nodeId(),
            new IsEqual<>("node-1")
        );
    }
}
