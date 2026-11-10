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
package com.auto1.pantera.test;

import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.cache.CacheBroadcast;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic, in-process {@link CacheBroadcast} fake for two-instance
 * cross-node propagation unit tests (revocation broadcast, policy
 * invalidation, settings-loader broadcast) — no Testcontainers Valkey
 * required. Every {@link InMemoryCacheBroadcast} constructed with the same
 * {@link Bus} simulates one Pantera node sharing that "cluster"; a publish
 * on one instance synchronously invokes the matching registered handler on
 * every <em>other</em> instance on the bus (self-messages are not delivered
 * back, mirroring {@code CacheInvalidationPubSub}'s self-message filter).
 * Synchronous delivery makes propagation assertions immediate — no
 * Awaitility polling needed for the fan-out itself.
 *
 * @since 2.3.0
 */
public final class InMemoryCacheBroadcast implements CacheBroadcast {

    /**
     * Shared bus this instance is a member of.
     */
    private final Bus bus;

    /**
     * This node's registered handlers, keyed by cache type.
     */
    private final Map<String, Cleanable<String>> handlers = new ConcurrentHashMap<>();

    /**
     * Ctor. Joins {@code bus} as a new node.
     * @param bus Shared bus simulating the cluster
     */
    public InMemoryCacheBroadcast(final Bus bus) {
        this.bus = bus;
        bus.join(this);
    }

    @Override
    public void publish(final String cacheType, final String key) {
        this.bus.members().stream()
            .filter(peer -> peer != this)
            .map(peer -> peer.handlers.get(cacheType))
            .filter(java.util.Objects::nonNull)
            .forEach(handler -> handler.invalidate(key));
    }

    @Override
    public void publishAll(final String cacheType) {
        this.bus.members().stream()
            .filter(peer -> peer != this)
            .map(peer -> peer.handlers.get(cacheType))
            .filter(java.util.Objects::nonNull)
            .forEach(Cleanable::invalidateAll);
    }

    @Override
    public void register(final String name, final Cleanable<String> cache) {
        this.handlers.put(name, cache);
    }

    /**
     * A simulated cluster: the shared membership list two or more
     * {@link InMemoryCacheBroadcast} instances join.
     * @since 2.3.0
     */
    public static final class Bus {

        /**
         * Nodes on this bus.
         */
        private final List<InMemoryCacheBroadcast> nodes = new CopyOnWriteArrayList<>();

        void join(final InMemoryCacheBroadcast node) {
            this.nodes.add(node);
        }

        List<InMemoryCacheBroadcast> members() {
            return this.nodes;
        }
    }
}
