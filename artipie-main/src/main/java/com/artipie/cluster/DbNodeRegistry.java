/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cluster;

import com.artipie.http.log.EcsLogger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * PostgreSQL-backed node registry for HA clustering.
 * <p>
 * Nodes register on startup, send periodic heartbeats, and are
 * automatically considered dead after missing heartbeats.
 * </p>
 * <p>
 * Schema: artipie_nodes(node_id VARCHAR PRIMARY KEY, hostname VARCHAR,
 *   port INT, started_at TIMESTAMP, last_heartbeat TIMESTAMP, status VARCHAR)
 * </p>
 *
 * @since 1.20.13
 */
public final class DbNodeRegistry {

    /**
     * Logger name for this class.
     */
    private static final String LOGGER = "com.artipie.cluster.DbNodeRegistry";

    /**
     * Node status: active.
     */
    private static final String STATUS_ACTIVE = "active";

    /**
     * Node status: stopped.
     */
    private static final String STATUS_STOPPED = "stopped";

    /**
     * Database source.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param source Database data source
     */
    public DbNodeRegistry(final DataSource source) {
        this.source = source;
    }

    /**
     * Create the artipie_nodes table if it does not exist.
     * Should be called once during application startup.
     * @throws SQLException On database error
     */
    public void createTable() throws SQLException {
        try (Connection conn = this.source.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                String.join(
                    "\n",
                    "CREATE TABLE IF NOT EXISTS artipie_nodes(",
                    "   node_id VARCHAR(255) PRIMARY KEY,",
                    "   hostname VARCHAR(255) NOT NULL,",
                    "   port INT NOT NULL,",
                    "   started_at TIMESTAMP NOT NULL,",
                    "   last_heartbeat TIMESTAMP NOT NULL,",
                    "   status VARCHAR(32) NOT NULL",
                    ");"
                )
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_nodes_status ON artipie_nodes(status)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_nodes_heartbeat ON artipie_nodes(last_heartbeat)"
            );
            EcsLogger.info(DbNodeRegistry.LOGGER)
                .message("artipie_nodes table initialized")
                .eventCategory("database")
                .eventAction("create_table")
                .eventOutcome("success")
                .log();
        }
    }

    /**
     * Register a node. If the node already exists, update its info (upsert).
     * Sets status to 'active' and refreshes heartbeat.
     * @param node Node info to register
     * @throws SQLException On database error
     */
    public void register(final NodeRegistry.NodeInfo node) throws SQLException {
        final Timestamp now = Timestamp.from(Instant.now());
        final Timestamp started = Timestamp.from(node.startedAt());
        try (Connection conn = this.source.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(
                String.join(
                    "\n",
                    "INSERT INTO artipie_nodes(node_id, hostname, port, started_at, last_heartbeat, status)",
                    "VALUES(?, ?, ?, ?, ?, ?)",
                    "ON CONFLICT(node_id) DO UPDATE SET",
                    "   hostname = EXCLUDED.hostname,",
                    "   port = EXCLUDED.port,",
                    "   started_at = EXCLUDED.started_at,",
                    "   last_heartbeat = EXCLUDED.last_heartbeat,",
                    "   status = EXCLUDED.status"
                )
            )) {
            pstmt.setString(1, node.nodeId());
            pstmt.setString(2, node.hostname());
            pstmt.setInt(3, 0);
            pstmt.setTimestamp(4, started);
            pstmt.setTimestamp(5, now);
            pstmt.setString(6, DbNodeRegistry.STATUS_ACTIVE);
            pstmt.executeUpdate();
            EcsLogger.info(DbNodeRegistry.LOGGER)
                .message("Node registered: " + node.nodeId())
                .eventCategory("cluster")
                .eventAction("node_register")
                .eventOutcome("success")
                .field("node.id", node.nodeId())
                .field("node.hostname", node.hostname())
                .log();
        }
    }

    /**
     * Send a heartbeat for the given node.
     * Updates last_heartbeat to current time and ensures status is 'active'.
     * @param nodeId Node identifier
     * @throws SQLException On database error
     */
    public void heartbeat(final String nodeId) throws SQLException {
        final Timestamp now = Timestamp.from(Instant.now());
        try (Connection conn = this.source.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE artipie_nodes SET last_heartbeat = ?, status = ? WHERE node_id = ?"
            )) {
            pstmt.setTimestamp(1, now);
            pstmt.setString(2, DbNodeRegistry.STATUS_ACTIVE);
            pstmt.setString(3, nodeId);
            final int updated = pstmt.executeUpdate();
            if (updated == 0) {
                EcsLogger.warn(DbNodeRegistry.LOGGER)
                    .message("Heartbeat for unknown node: " + nodeId)
                    .eventCategory("cluster")
                    .eventAction("node_heartbeat")
                    .eventOutcome("failure")
                    .field("node.id", nodeId)
                    .log();
            } else {
                EcsLogger.debug(DbNodeRegistry.LOGGER)
                    .message("Heartbeat received: " + nodeId)
                    .eventCategory("cluster")
                    .eventAction("node_heartbeat")
                    .eventOutcome("success")
                    .field("node.id", nodeId)
                    .log();
            }
        }
    }

    /**
     * Deregister a node by setting its status to 'stopped'.
     * The node row is retained for audit purposes; use {@link #evictStale}
     * to physically remove old entries.
     * @param nodeId Node identifier
     * @throws SQLException On database error
     */
    public void deregister(final String nodeId) throws SQLException {
        try (Connection conn = this.source.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE artipie_nodes SET status = ? WHERE node_id = ?"
            )) {
            pstmt.setString(1, DbNodeRegistry.STATUS_STOPPED);
            pstmt.setString(2, nodeId);
            pstmt.executeUpdate();
            EcsLogger.info(DbNodeRegistry.LOGGER)
                .message("Node deregistered: " + nodeId)
                .eventCategory("cluster")
                .eventAction("node_deregister")
                .eventOutcome("success")
                .field("node.id", nodeId)
                .log();
        }
    }

    /**
     * Get all nodes whose last heartbeat is within the given timeout
     * and whose status is 'active'.
     * @param heartbeatTimeoutMs Maximum age of heartbeat in milliseconds
     * @return List of live node info records
     * @throws SQLException On database error
     */
    public List<NodeRegistry.NodeInfo> liveNodes(final long heartbeatTimeoutMs)
        throws SQLException {
        final Timestamp cutoff = Timestamp.from(
            Instant.now().minusMillis(heartbeatTimeoutMs)
        );
        final List<NodeRegistry.NodeInfo> result = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(
                String.join(
                    "\n",
                    "SELECT node_id, hostname, started_at, last_heartbeat",
                    "FROM artipie_nodes",
                    "WHERE status = ? AND last_heartbeat >= ?",
                    "ORDER BY started_at"
                )
            )) {
            pstmt.setString(1, DbNodeRegistry.STATUS_ACTIVE);
            pstmt.setTimestamp(2, cutoff);
            try (ResultSet rset = pstmt.executeQuery()) {
                while (rset.next()) {
                    result.add(
                        new NodeRegistry.NodeInfo(
                            rset.getString("node_id"),
                            rset.getString("hostname"),
                            rset.getTimestamp("started_at").toInstant(),
                            rset.getTimestamp("last_heartbeat").toInstant()
                        )
                    );
                }
            }
        }
        EcsLogger.debug(DbNodeRegistry.LOGGER)
            .message("Live nodes query returned " + result.size() + " nodes")
            .eventCategory("cluster")
            .eventAction("live_nodes_query")
            .eventOutcome("success")
            .field("cluster.live_count", result.size())
            .field("cluster.heartbeat_timeout_ms", heartbeatTimeoutMs)
            .log();
        return result;
    }

    /**
     * Remove stale nodes whose last heartbeat is older than the given timeout.
     * This physically deletes the rows from the database.
     * @param heartbeatTimeoutMs Maximum age of heartbeat in milliseconds
     * @return Number of evicted nodes
     * @throws SQLException On database error
     */
    public int evictStale(final long heartbeatTimeoutMs) throws SQLException {
        final Timestamp cutoff = Timestamp.from(
            Instant.now().minusMillis(heartbeatTimeoutMs)
        );
        final int evicted;
        try (Connection conn = this.source.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(
                "DELETE FROM artipie_nodes WHERE last_heartbeat < ?"
            )) {
            pstmt.setTimestamp(1, cutoff);
            evicted = pstmt.executeUpdate();
        }
        if (evicted > 0) {
            EcsLogger.info(DbNodeRegistry.LOGGER)
                .message("Evicted " + evicted + " stale nodes")
                .eventCategory("cluster")
                .eventAction("node_evict")
                .eventOutcome("success")
                .field("cluster.evicted_count", evicted)
                .field("cluster.heartbeat_timeout_ms", heartbeatTimeoutMs)
                .log();
        }
        return evicted;
    }
}
