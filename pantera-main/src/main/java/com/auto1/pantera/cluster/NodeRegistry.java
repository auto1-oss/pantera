/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry of cluster nodes for HA coordination.
 * In-memory implementation for single-node mode.
 * PostgreSQL-backed implementation for multi-node clusters.
 *
 * @since 1.20.13
 */
public final class NodeRegistry {

    /**
     * Default stale threshold.
     */
    private static final Duration STALE_THRESHOLD = Duration.ofSeconds(30);

    /**
     * This node's ID.
     */
    private final String nodeId;

    /**
     * This node's hostname.
     */
    private final String hostname;

    /**
     * Registered nodes (nodeId -> NodeInfo).
     */
    private final Map<String, NodeInfo> nodes;

    /**
     * Ctor with auto-generated node ID.
     * @param hostname This node's hostname
     */
    public NodeRegistry(final String hostname) {
        this(UUID.randomUUID().toString(), hostname);
    }

    /**
     * Ctor.
     * @param nodeId This node's unique ID
     * @param hostname This node's hostname
     */
    public NodeRegistry(final String nodeId, final String hostname) {
        this.nodeId = nodeId;
        this.hostname = hostname;
        this.nodes = new ConcurrentHashMap<>();
        // Register self
        this.nodes.put(nodeId, new NodeInfo(nodeId, hostname, Instant.now(), Instant.now()));
    }

    /**
     * Record a heartbeat for this node.
     */
    public void heartbeat() {
        this.nodes.compute(this.nodeId, (id, existing) -> {
            if (existing != null) {
                return new NodeInfo(id, this.hostname, existing.startedAt(), Instant.now());
            }
            return new NodeInfo(id, this.hostname, Instant.now(), Instant.now());
        });
    }

    /**
     * Get all active (non-stale) nodes.
     * @return List of active node info
     */
    public List<NodeInfo> activeNodes() {
        final Instant cutoff = Instant.now().minus(STALE_THRESHOLD);
        return this.nodes.values().stream()
            .filter(n -> n.lastHeartbeat().isAfter(cutoff))
            .collect(Collectors.toList());
    }

    /**
     * Get this node's ID.
     * @return Node ID
     */
    public String nodeId() {
        return this.nodeId;
    }

    /**
     * Get total registered node count.
     * @return Node count
     */
    public int size() {
        return this.nodes.size();
    }

    /**
     * Node information record.
     * @param nodeId Node ID
     * @param hostname Node hostname
     * @param startedAt When the node started
     * @param lastHeartbeat Last heartbeat timestamp
     */
    public record NodeInfo(
        String nodeId, String hostname, Instant startedAt, Instant lastHeartbeat
    ) { }
}
