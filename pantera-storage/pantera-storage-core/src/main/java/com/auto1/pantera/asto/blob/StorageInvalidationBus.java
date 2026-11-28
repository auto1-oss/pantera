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

/**
 * Minimal cross-node coherence bus {@link CachedBlobStorage} publishes to
 * (and receives from) on write-through/write-back commit and delete (spec
 * {@code WS1-storage-for-scale.md} &sect;3.E, phase WS1.5) -- a peer that
 * receives a publish for a key it has cached locally drops its own
 * disk+index entry so the NEXT access re-resolves it, replacing the
 * per-read validation HEAD WS1.1 deliberately removed from the hot path
 * with event-driven invalidation (the {@code freshnessTtl} WS1.1 already
 * has remains the backstop for the window before a message arrives, or if a
 * message is lost).
 *
 * <p><strong>Why this interface lives here, in {@code pantera-storage-core},
 * and not in {@code pantera-core}:</strong> {@link CachedBlobStorage} sits
 * BELOW {@code pantera-core} in the module graph and cannot depend on {@code
 * pantera-core}'s {@code CacheInvalidationPubSub} directly -- exactly the
 * same layering constraint WS1.1 hit with {@code SingleFlight}. This
 * interface is the dependency-inversion seam: {@code pantera-storage-core}
 * defines the minimal contract a bus must satisfy and ships {@link #NOOP} as
 * the default (pure single-instance mode, or any repository whose storage
 * was not explicitly handed a bus); the REAL implementation -- wrapping
 * {@code CacheInvalidationPubSub} on a new {@code "storage"} cache-type
 * channel -- lives in {@code pantera-core} ({@code
 * PubSubStorageInvalidationBus}) and is wired in wherever a {@link
 * CachedBlobStorage} is constructed (see {@code StorageInvalidationBusRegistry}
 * for the static-install seam a lower-module {@code StorageFactory} uses to
 * obtain it, since the {@code StorageFactory} SPI itself carries only a
 * {@code Config}).</p>
 *
 * <p><strong>Implementation contract:</strong></p>
 * <ul>
 *   <li>A publish by one node MUST NOT be delivered back to that SAME node's
 *   own {@link #onInvalidate} listener(s) (self-message filtering) -- the
 *   real implementation inherits this for free by delegating to {@code
 *   CacheInvalidationPubSub}'s existing instance-UUID filter.</li>
 *   <li>{@link #onInvalidate} MUST support multiple registrations and fan a
 *   delivered message out to every one of them: several {@link
 *   CachedBlobStorage} instances (one per repository configured with {@code
 *   cache.mode: index}) share ONE process-wide bus/channel. Namespace
 *   disambiguation between repositories happens INSIDE {@code
 *   CachedBlobStorage} (via its {@code StorageInvalidationToken}), not here
 *   -- this interface is a dumb relay of an opaque string.</li>
 * </ul>
 *
 * @since 2.3.0
 */
public interface StorageInvalidationBus {

    /**
     * No-op bus: the default in pure single-instance mode (no
     * clustering/Valkey configured) or for any repository whose storage was
     * not explicitly handed a bus. {@link #publish} does nothing (there are
     * no peers to notify); {@link #onInvalidate} discards the listener
     * (nothing will ever be delivered to it) -- together these preserve
     * exactly the pre-WS1.5 {@code CachedBlobStorage} behaviour.
     */
    StorageInvalidationBus NOOP = new StorageInvalidationBus() {
        @Override
        public void publish(final Key key, final String versionToken) {
            // no-op: single-instance mode has no peers to notify
        }

        @Override
        public void onInvalidate(final StorageInvalidationListener listener) {
            // no-op: this bus never delivers anything, so nothing to register
        }
    };

    /**
     * Publish a coherence invalidation for {@code key}.
     *
     * @param key Key that was committed (written durably) or deleted.
     * @param versionToken Opaque, publisher-defined version marker -- the
     *  bus never interprets this string, it only relays it verbatim to
     *  every registered peer listener.
     */
    void publish(Key key, String versionToken);

    /**
     * Register a listener invoked for every invalidation this bus delivers
     * (i.e. every OTHER node's {@link #publish}, never this node's own --
     * see the class javadoc's self-filtering contract). Supports multiple
     * registrations; every registered listener is invoked for every
     * delivered message.
     *
     * @param listener Listener to register.
     */
    void onInvalidate(StorageInvalidationListener listener);
}
