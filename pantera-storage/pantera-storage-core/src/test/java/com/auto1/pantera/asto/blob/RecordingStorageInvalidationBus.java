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
package com.auto1.pantera.asto.blob;

import com.auto1.pantera.asto.Key;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic in-process {@link StorageInvalidationBus} fake simulating a
 * shared cluster "wire" -- the WS1.5 analogue of {@code InMemoryCacheBroadcast}
 * (pantera-main test fixture for {@code CacheBroadcast}), rebuilt here
 * because {@code pantera-storage-core} cannot reach into {@code
 * pantera-main}'s test sources.
 *
 * <p>Every {@link Node} created by {@link #newNode()} joins this SAME shared
 * bus: a {@link Node#publish(Key, String)} on one node is delivered
 * synchronously to every OTHER node's registered {@link
 * StorageInvalidationListener}s -- never back to the publishing node itself
 * (self-message filtering, mirroring {@code CacheInvalidationPubSub}'s
 * instance-UUID filter, proved directly by {@code
 * CachedBlobStorageInvalidationTest}). {@link Node#deliverDirectly(Key,
 * String)} bypasses routing/self-filtering entirely to hand a
 * test-fabricated message straight to one node's own listeners, for tests
 * that need to simulate a specific (e.g. stale/reordered) message without
 * constructing a second full peer.
 *
 * @since 2.3.0
 */
final class RecordingStorageInvalidationBus {

    /**
     * Nodes that have joined this bus.
     */
    private final List<Node> nodes = new CopyOnWriteArrayList<>();

    /**
     * Joins a new node to this bus.
     *
     * @return The new node's {@link StorageInvalidationBus} view.
     */
    Node newNode() {
        final Node node = new Node();
        this.nodes.add(node);
        return node;
    }

    /**
     * One simulated cluster node's view of the shared bus.
     *
     * @since 2.3.0
     */
    final class Node implements StorageInvalidationBus {

        /**
         * Listeners registered on THIS node.
         */
        private final List<StorageInvalidationListener> listeners = new CopyOnWriteArrayList<>();

        @Override
        public void publish(final Key key, final String versionToken) {
            for (final Node peer : RecordingStorageInvalidationBus.this.nodes) {
                if (peer != this) { // NOPMD CompareObjectsWithEquals - intentional identity check (self-message filter)
                    for (final StorageInvalidationListener listener : peer.listeners) {
                        listener.invalidated(key, versionToken);
                    }
                }
            }
        }

        @Override
        public void onInvalidate(final StorageInvalidationListener listener) {
            this.listeners.add(listener);
        }

        /**
         * Delivers {@code (key, versionToken)} directly to this node's own
         * registered listeners, bypassing {@link #publish}'s peer-routing
         * and self-filter -- lets a test hand-craft an arbitrary (e.g. stale)
         * message as if it arrived from some peer, without needing a second
         * full node.
         *
         * @param key Key.
         * @param versionToken Encoded token.
         */
        void deliverDirectly(final Key key, final String versionToken) {
            for (final StorageInvalidationListener listener : this.listeners) {
                listener.invalidated(key, versionToken);
            }
        }
    }
}
