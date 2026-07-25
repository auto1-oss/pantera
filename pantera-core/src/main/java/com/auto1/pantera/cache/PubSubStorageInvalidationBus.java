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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blob.StorageInvalidationBus;
import com.auto1.pantera.asto.blob.StorageInvalidationListener;
import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real {@link StorageInvalidationBus} implementation (WS1.5, spec {@code
 * WS1-storage-for-scale.md} &sect;3.E): relays {@code CachedBlobStorage}
 * (pantera-storage-core) cross-node coherence messages over the existing
 * {@link CacheBroadcast} -- {@link CacheInvalidationPubSub} in production, a
 * deterministic in-process fake in cross-instance unit tests -- on a NEW
 * {@value #CHANNEL} cache-type/channel, distinct from the auth/filters/policy
 * channels {@code CacheBroadcast} already multiplexes over the same
 * underlying Valkey pub/sub connection.
 *
 * <p>Every {@code CachedBlobStorage} instance in the process (one per
 * repository configured with {@code cache.mode: index}) shares ONE {@link
 * PubSubStorageInvalidationBus} and registers its own {@link
 * StorageInvalidationListener} via {@link #onInvalidate}; delivery fans out
 * to every registered listener -- namespace disambiguation between
 * repositories happens INSIDE {@code CachedBlobStorage} itself (see its
 * {@code StorageInvalidationToken}), not here: this class is a dumb relay of
 * an opaque {@code (key, versionToken)} pair.</p>
 *
 * <p>Self-message filtering (a node never receives its own {@link
 * #publish}) is inherited for free from {@link CacheBroadcast}'s existing
 * implementation ({@link CacheInvalidationPubSub}'s instance-UUID filter) --
 * this class adds none of its own.</p>
 *
 * @since 2.3.0
 */
public final class PubSubStorageInvalidationBus implements StorageInvalidationBus {

    /**
     * {@link CacheBroadcast} cache-type/channel name this bus registers
     * under -- the spec's "new storage channel" (&sect;3.E), multiplexed
     * over the SAME underlying Valkey pub/sub channel {@link
     * CacheInvalidationPubSub} already uses for every other cache type.
     */
    static final String CHANNEL = "storage";

    /**
     * Separator between the logical storage key and its version token in
     * the single string {@link CacheBroadcast#publish(String, String)}
     * carries -- {@code CacheBroadcast}'s "key" parameter is repurposed to
     * carry BOTH fields, exactly as {@code CachedBlobStorage}'s own {@code
     * StorageInvalidationToken} repurposes ITS single string parameter to
     * carry namespace+digest+timestamp.
     */
    private static final char KEY_TOKEN_SEPARATOR = '\u0002';

    /**
     * Registered listeners, fanned out to on every delivered message.
     */
    private final List<StorageInvalidationListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Underlying broadcast (production: Valkey-backed {@link
     * CacheInvalidationPubSub}; tests: a deterministic in-process fake).
     */
    private final CacheBroadcast broadcast;

    /**
     * New bus over {@code broadcast}, registering immediately (rather than
     * lazily on first {@link #onInvalidate}) so this bus never depends on
     * registration ordering relative to peers starting to publish.
     *
     * @param broadcast Underlying cross-instance broadcast.
     */
    public PubSubStorageInvalidationBus(final CacheBroadcast broadcast) {
        this.broadcast = broadcast;
        this.broadcast.register(PubSubStorageInvalidationBus.CHANNEL, new Relay());
    }

    @Override
    public void publish(final Key key, final String versionToken) {
        this.broadcast.publish(
            PubSubStorageInvalidationBus.CHANNEL,
            PubSubStorageInvalidationBus.encode(key, versionToken)
        );
        EcsLogger.debug("com.auto1.pantera.cache")
            .message("Published storage cross-node invalidation")
            .eventCategory("database")
            .eventAction("storage_invalidation_publish")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    @Override
    public void onInvalidate(final StorageInvalidationListener listener) {
        this.listeners.add(listener);
    }

    private void dispatch(final String wireMessage) {
        final int sep = wireMessage.indexOf(PubSubStorageInvalidationBus.KEY_TOKEN_SEPARATOR);
        if (sep < 0) {
            EcsLogger.warn("com.auto1.pantera.cache")
                .message("Malformed storage invalidation message received; dropping")
                .eventCategory("database")
                .eventAction("storage_invalidation_receive")
                .eventOutcome("failure")
                .field("log.source", "application")
                .log();
            return;
        }
        final Key key = new Key.From(wireMessage.substring(0, sep));
        final String token = wireMessage.substring(sep + 1);
        EcsLogger.debug("com.auto1.pantera.cache")
            .message("Received storage cross-node invalidation")
            .eventCategory("database")
            .eventAction("storage_invalidation_receive")
            .eventOutcome("success")
            .log();
        for (final StorageInvalidationListener listener : this.listeners) {
            listener.invalidated(key, token);
        }
    }

    private static String encode(final Key key, final String versionToken) {
        return key.string() + PubSubStorageInvalidationBus.KEY_TOKEN_SEPARATOR + versionToken;
    }

    /**
     * {@link Cleanable} adapter registered with {@link #broadcast}: {@code
     * CacheBroadcast}'s contract is a per-key {@code invalidate(String)}
     * plus an {@code invalidateAll()} this bus has no use for (storage
     * coherence messages are always per-key).
     *
     * @since 2.3.0
     */
    private final class Relay implements Cleanable<String> {
        @Override
        public void invalidate(final String wireMessage) {
            PubSubStorageInvalidationBus.this.dispatch(wireMessage);
        }

        @Override
        public void invalidateAll() {
            // no-op: storage coherence messages are always per-key; this bus
            // never publishes/expects a broadcast "drop everything" message.
        }
    }
}
