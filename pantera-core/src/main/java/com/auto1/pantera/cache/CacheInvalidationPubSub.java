/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cache;

import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.http.log.EcsLogger;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * Message format: {@code instanceId|cacheType|key}
 * <br>
 * For invalidateAll: {@code instanceId|cacheType|*}
 *
 * @since 1.20.13
 */
public final class CacheInvalidationPubSub implements AutoCloseable {

    /**
     * Redis channel name for cache invalidation messages.
     */
    static final String CHANNEL = "artipie:cache:invalidate";

    /**
     * Wildcard key used for invalidateAll messages.
     */
    private static final String ALL = "*";

    /**
     * Message field separator.
     */
    private static final String SEP = "|";

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
        this.subConn.async().subscribe(CacheInvalidationPubSub.CHANNEL);
        EcsLogger.info("com.auto1.pantera.cache")
            .message("Cache invalidation pub/sub started (instance: "
                + this.instanceId.substring(0, 8) + ")")
            .eventCategory("cache")
            .eventAction("pubsub_start")
            .eventOutcome("success")
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
     * Publish an invalidation message for a specific key.
     * Other instances will call {@code cache.invalidate(key)} on receipt.
     * @param cacheType Cache type name
     * @param key Cache key to invalidate
     */
    public void publish(final String cacheType, final String key) {
        final String msg = String.join(
            CacheInvalidationPubSub.SEP, this.instanceId, cacheType, key
        );
        this.pubCommands.publish(CacheInvalidationPubSub.CHANNEL, msg);
    }

    /**
     * Publish an invalidateAll message.
     * Other instances will call {@code cache.invalidateAll()} on receipt.
     * @param cacheType Cache type name
     */
    public void publishAll(final String cacheType) {
        final String msg = String.join(
            CacheInvalidationPubSub.SEP, this.instanceId, cacheType,
            CacheInvalidationPubSub.ALL
        );
        this.pubCommands.publish(CacheInvalidationPubSub.CHANNEL, msg);
    }

    @Override
    public void close() {
        this.subConn.close();
        this.pubConn.close();
        EcsLogger.info("com.auto1.pantera.cache")
            .message("Cache invalidation pub/sub closed")
            .eventCategory("cache")
            .eventAction("pubsub_stop")
            .eventOutcome("success")
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
            final String[] parts = message.split(
                "\\" + CacheInvalidationPubSub.SEP, 3
            );
            if (parts.length < 3) {
                return;
            }
            final String sender = parts[0];
            if (CacheInvalidationPubSub.this.instanceId.equals(sender)) {
                return;
            }
            final String cacheType = parts[1];
            final String key = parts[2];
            final Cleanable<String> cache =
                CacheInvalidationPubSub.this.caches.get(cacheType);
            if (cache == null) {
                return;
            }
            if (CacheInvalidationPubSub.ALL.equals(key)) {
                cache.invalidateAll();
            } else {
                cache.invalidate(key);
            }
            EcsLogger.debug("com.auto1.pantera.cache")
                .message("Remote cache invalidation: " + cacheType + ":" + key)
                .eventCategory("cache")
                .eventAction("remote_invalidate")
                .eventOutcome("success")
                .log();
        }
    }
}
