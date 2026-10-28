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
package com.auto1.pantera.cache;

import com.auto1.pantera.asto.misc.Cleanable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic, in-process {@link CacheBroadcast} fake for two-instance
 * cross-node propagation unit tests -- the {@code pantera-core}-local
 * equivalent of {@code InMemoryCacheBroadcast} (a {@code pantera-main} test
 * fixture, unreachable from {@code pantera-core}'s own test sources).
 *
 * <p>Every {@link FakeCacheBroadcast} constructed with the same {@link Bus}
 * simulates one Pantera node sharing that "cluster"; a publish on one
 * instance synchronously invokes the matching registered handler on every
 * OTHER instance on the bus (self-messages are never delivered back,
 * mirroring {@code CacheInvalidationPubSub}'s instance-UUID self-message
 * filter).
 *
 * @since 2.3.0
 */
final class FakeCacheBroadcast implements CacheBroadcast {

    /**
     * Shared bus this instance is a member of.
     */
    private final Bus bus;

    /**
     * This node's registered handlers, keyed by cache type.
     */
    private final Map<String, Cleanable<String>> handlers = new ConcurrentHashMap<>();

    /**
     * Joins {@code bus} as a new node.
     *
     * @param bus Shared bus simulating the cluster.
     */
    FakeCacheBroadcast(final Bus bus) {
        this.bus = bus;
        bus.join(this);
    }

    @Override
    public void publish(final String cacheType, final String key) {
        this.bus.members().stream()
            .filter(peer -> peer != this) // NOPMD CompareObjectsWithEquals - intentional identity check (self-message filter)
            .map(peer -> peer.handlers.get(cacheType))
            .filter(java.util.Objects::nonNull)
            .forEach(handler -> handler.invalidate(key));
    }

    @Override
    public void publishAll(final String cacheType) {
        this.bus.members().stream()
            .filter(peer -> peer != this) // NOPMD CompareObjectsWithEquals - intentional identity check (self-message filter)
            .map(peer -> peer.handlers.get(cacheType))
            .filter(java.util.Objects::nonNull)
            .forEach(Cleanable::invalidateAll);
    }

    @Override
    public void register(final String name, final Cleanable<String> cache) {
        this.handlers.put(name, cache);
    }

    /**
     * A simulated cluster: the shared membership list two or more {@link
     * FakeCacheBroadcast} instances join.
     *
     * @since 2.3.0
     */
    static final class Bus {

        /**
         * Nodes on this bus.
         */
        private final List<FakeCacheBroadcast> nodes = new CopyOnWriteArrayList<>();

        void join(final FakeCacheBroadcast node) {
            this.nodes.add(node);
        }

        List<FakeCacheBroadcast> members() {
            return this.nodes;
        }
    }
}
