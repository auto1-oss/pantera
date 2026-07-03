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
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.MDC;

/**
 * Redis/Valkey pub/sub channel for cross-instance cache invalidation.
 * <p>
 * When multiple Pantera instances share a Valkey/Redis server, local
 * Caffeine caches can become stale when another instance modifies data.
 * This class uses Redis pub/sub to broadcast invalidation messages so
 * all instances stay in sync.
 * <p>
 * Each instance generates a unique {@code instanceId} on startup.
 * Messages published by this instance are ignored on receipt to avoid
 * invalidating caches that were already updated locally.
 * <p>
 * Wire format (current, v2):
 * {@code v2:instanceId|traceId|spanId|cacheType|key} — the {@code v2:}
 * prefix carries the originating-request trace context so the receiver
 * can restore MDC trace.id / span.id for the duration of the
 * invalidate() callback. Legacy {@code instanceId|cacheType|key} (v1)
 * is still parsed for rolling-deploy compatibility — handler runs with
 * no trace MDC, exactly as before this change.
 * <br>
 * For invalidateAll the {@code key} field is the wildcard {@code *}.
 *
 * @since 1.20.13
 */
public final class CacheInvalidationPubSub implements AutoCloseable {

    /**
     * Redis channel name for cache invalidation messages.
     */
    static final String CHANNEL = "pantera:cache:invalidate";

    /**
     * Wildcard key used for invalidateAll messages.
     */
    private static final String ALL = "*";

    /**
     * Message field separator.
     */
    private static final String SEP = "|";

    /**
     * Wire-format version prefix that prepends trace.id / span.id between
     * the cacheType/key fields and the rest of the envelope. Receivers
     * that see this prefix restore the trace context into MDC for the
     * handler callback; v1 receivers see only the original 3-field
     * envelope and degrade gracefully (no MDC restore).
     */
    private static final String PAYLOAD_VERSION_TRACE = "v2:";

    /**
     * Unique instance identifier to filter out self-messages.
     */
    private final String instanceId;

    /**
     * Connection for subscribing (receiving messages).
     */
    private final StatefulRedisPubSubConnection<String, String> subConn;

    /**
     * Connection for publishing (sending messages).
     * Pub/sub spec requires separate connections for sub and pub.
     */
    private final StatefulRedisPubSubConnection<String, String> pubConn;

    /**
     * Async publish commands.
     */
    private final RedisPubSubAsyncCommands<String, String> pubCommands;

    /**
     * Registered cache handlers keyed by cache type name.
     */
    private final Map<String, Cleanable<String>> caches;

    /**
     * Ctor.
     * @param valkey Valkey connection to create pub/sub connections from
     */
    public CacheInvalidationPubSub(final ValkeyConnection valkey) {
        this.instanceId = UUID.randomUUID().toString();
        this.subConn = valkey.connectPubSub();
        this.pubConn = valkey.connectPubSub();
        this.pubCommands = this.pubConn.async();
        this.caches = new ConcurrentHashMap<>();
        this.subConn.addListener(new Listener());
        // Synchronous subscribe: block (boot thread only, never the event
        // loop) until the server acks the subscription. The async variant
        // returned before the SUBSCRIBE landed, so invalidations broadcast
        // during this instance's startup window were silently missed —
        // observed as a lost-message race under CI load.
        this.subConn.sync().subscribe(CacheInvalidationPubSub.CHANNEL);
        EcsLogger.info("com.auto1.pantera.cache")
            .message("Cache invalidation pub/sub started (instance: "
                + this.instanceId.substring(0, 8) + ")")
            .eventCategory("database")
            .eventAction("pubsub_start")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    /**
     * Register a cache for remote invalidation.
     * @param name Cache type name (e.g. "auth", "filters", "policy")
     * @param cache Cache instance to invalidate on remote messages
     */
    public void register(final String name, final Cleanable<String> cache) {
        this.caches.put(name, cache);
    }

    /**
     * Subscribe a per-key invalidation handler under the given namespace.
     * Convenience wrapper around {@link #register(String, Cleanable)} for
     * callers that only care about per-key invalidation and don't need the
     * {@link Cleanable#invalidateAll()} broadcast — the adapter no-ops on
     * invalidateAll because the handler is per-key only.
     *
     * @param namespace Cache type / namespace name (e.g. "auth:enabled")
     * @param handler Consumer invoked with the key when a remote invalidation
     *     for this namespace arrives
     */
    public void subscribe(final String namespace, final Consumer<String> handler) {
        this.caches.put(namespace, new Cleanable<>() {
            @Override
            public void invalidate(final String key) {
                handler.accept(key);
            }

            @Override
            public void invalidateAll() {
                // No-op: consumer-based subscribers only care about per-key
                // invalidation; invalidateAll messages for this namespace
                // are ignored by design.
            }
        });
    }

    /**
     * Publish an invalidation message for a specific key.
     * Other instances will call {@code cache.invalidate(key)} on receipt.
     * @param cacheType Cache type name
     * @param key Cache key to invalidate
     */
    public void publish(final String cacheType, final String key) {
        this.pubCommands.publish(
            CacheInvalidationPubSub.CHANNEL,
            wireMessage(cacheType, key)
        );
    }

    /**
     * Publish an invalidateAll message.
     * Other instances will call {@code cache.invalidateAll()} on receipt.
     * @param cacheType Cache type name
     */
    public void publishAll(final String cacheType) {
        this.pubCommands.publish(
            CacheInvalidationPubSub.CHANNEL,
            wireMessage(cacheType, CacheInvalidationPubSub.ALL)
        );
    }

    /**
     * Encode the v2 wire envelope:
     * {@code v2:<instanceId>|<traceId>|<spanId>|<cacheType>|<key>}.
     * trace.id / span.id are sourced from MDC (empty if absent).
     */
    private String wireMessage(final String cacheType, final String key) {
        final String traceId = nullToEmpty(MDC.get(EcsMdc.TRACE_ID));
        final String spanId = nullToEmpty(MDC.get(EcsMdc.SPAN_ID));
        return CacheInvalidationPubSub.PAYLOAD_VERSION_TRACE
            + String.join(
                CacheInvalidationPubSub.SEP,
                this.instanceId, traceId, spanId, cacheType, key
            );
    }

    /** Coalesce nulls to empty strings to keep the wire format positional. */
    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    @Override
    public void close() {
        this.subConn.close();
        this.pubConn.close();
        EcsLogger.info("com.auto1.pantera.cache")
            .message("Cache invalidation pub/sub closed")
            .eventCategory("database")
            .eventAction("pubsub_stop")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    /**
     * Listener that receives pub/sub messages and dispatches to caches.
     */
    private final class Listener extends RedisPubSubAdapter<String, String> {
        @Override
        public void message(final String channel, final String message) {
            if (!CacheInvalidationPubSub.CHANNEL.equals(channel)) {
                return;
            }
            // Decode wire envelope. v2 carries trace.id + span.id between
            // the instance id and the cacheType/key pair. Anything without
            // the prefix is a v1 message from an older instance still in
            // the rolling deploy — fall back to the legacy 3-field parse.
            final String body;
            final boolean v2;
            if (message.startsWith(CacheInvalidationPubSub.PAYLOAD_VERSION_TRACE)) {
                body = message.substring(
                    CacheInvalidationPubSub.PAYLOAD_VERSION_TRACE.length()
                );
                v2 = true;
            } else {
                body = message;
                v2 = false;
            }
            final String sender;
            final String traceId;
            final String spanId;
            final String cacheType;
            final String key;
            if (v2) {
                final String[] parts = body.split(
                    "\\" + CacheInvalidationPubSub.SEP, 5
                );
                if (parts.length < 5) {
                    return;
                }
                sender = parts[0];
                traceId = parts[1];
                spanId = parts[2];
                cacheType = parts[3];
                key = parts[4];
            } else {
                final String[] parts = body.split(
                    "\\" + CacheInvalidationPubSub.SEP, 3
                );
                if (parts.length < 3) {
                    return;
                }
                sender = parts[0];
                traceId = "";
                spanId = "";
                cacheType = parts[1];
                key = parts[2];
            }
            if (CacheInvalidationPubSub.this.instanceId.equals(sender)) {
                return;
            }
            final Cleanable<String> cache =
                CacheInvalidationPubSub.this.caches.get(cacheType);
            if (cache == null) {
                return;
            }
            final boolean restoreTrace = !traceId.isEmpty();
            if (restoreTrace) {
                MDC.put(EcsMdc.TRACE_ID, traceId);
                if (!spanId.isEmpty()) {
                    MDC.put(EcsMdc.SPAN_ID, spanId);
                }
            }
            try {
                if (CacheInvalidationPubSub.ALL.equals(key)) {
                    cache.invalidateAll();
                } else {
                    cache.invalidate(key);
                }
                EcsLogger.debug("com.auto1.pantera.cache")
                    .message(
                        "Remote cache invalidation: " + cacheType + ":" + key
                    )
                    .eventCategory("database")
                    .eventAction("remote_invalidate")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            } finally {
                if (restoreTrace) {
                    MDC.remove(EcsMdc.TRACE_ID);
                    if (!spanId.isEmpty()) {
                        MDC.remove(EcsMdc.SPAN_ID);
                    }
                }
            }
        }
    }
}
