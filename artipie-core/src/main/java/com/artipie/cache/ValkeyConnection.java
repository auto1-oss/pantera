/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Valkey/Redis connection for L2 cache across Artipie.
 * Shared connection used by all two-tier caches.
 * Thread-safe, async operations.
 *
 * @since 1.0
 */
public final class ValkeyConnection implements AutoCloseable {

    /**
     * Redis client.
     */
    private final RedisClient client;

    /**
     * Stateful connection.
     */
    private final StatefulRedisConnection<String, byte[]> connection;

    /**
     * Async commands interface.
     */
    private final RedisAsyncCommands<String, byte[]> async;

    /**
     * Pub/Sub connection (lazy initialized).
     */
    private volatile StatefulRedisPubSubConnection<String, String> pubSubConnection;

    /**
     * Pub/Sub async commands (lazy initialized).
     */
    private volatile RedisPubSubAsyncCommands<String, String> pubSubAsync;

    /**
     * Subscribers waiting for channel messages.
     * Key: channel name, Value: list of subscription entries (multiple callbacks per channel).
     */
    private final Map<String, List<SubscriptionEntry>> subscribers;

    /**
     * Constructor from configuration.
     *
     * @param config Cache configuration with Valkey settings
     */
    public ValkeyConnection(final CacheConfig config) {
        this(
            config.valkeyHost().orElse("localhost"),
            config.valkeyPort().orElse(6379),
            config.valkeyTimeout().orElse(Duration.ofMillis(100))
        );
    }

    /**
     * Constructor with explicit parameters.
     *
     * @param host Valkey/Redis host
     * @param port Valkey/Redis port
     * @param timeout Request timeout
     */
    public ValkeyConnection(
        final String host,
        final int port,
        final Duration timeout
    ) {
        this.client = RedisClient.create(
            RedisURI.builder()
                .withHost(Objects.requireNonNull(host))
                .withPort(port)
                .withTimeout(timeout)
                .build()
        );
        // Use String keys and byte[] values
        final RedisCodec<String, byte[]> codec = RedisCodec.of(
            StringCodec.UTF8,
            ByteArrayCodec.INSTANCE
        );
        this.connection = this.client.connect(codec);
        this.async = this.connection.async();
        // Enable pipelining for better throughput
        this.async.setAutoFlushCommands(true);
        // Initialize subscribers map
        this.subscribers = new ConcurrentHashMap<>();
    }

    /**
     * Get async commands interface.
     *
     * @return Redis async commands
     */
    public RedisAsyncCommands<String, byte[]> async() {
        return this.async;
    }

    /**
     * Ping to check connectivity (blocking).
     * WARNING: Blocks calling thread. Use pingAsync() for non-blocking health checks.
     *
     * @return True if connected
     * @deprecated Use pingAsync() to avoid blocking
     */
    @Deprecated
    public boolean ping() {
        try {
            return "PONG".equals(this.async.ping().get());
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Async ping to check connectivity (non-blocking).
     * Preferred over blocking ping() method.
     *
     * @return Future with true if connected, false on timeout or error
     */
    public CompletableFuture<Boolean> pingAsync() {
        return this.async.ping()
            .toCompletableFuture()
            .orTimeout(1000, TimeUnit.MILLISECONDS)
            .thenApply(pong -> "PONG".equals(pong))
            .exceptionally(err -> false);
    }

    /**
     * Subscribe to a channel and invoke callback when message received.
     * Uses lazy-initialized pub/sub connection. Multiple callbacks can be
     * registered for the same channel.
     *
     * @param channel Channel name to subscribe to
     * @param callback Callback invoked with message when received
     * @return Future completing with subscription ID (use to unsubscribe specific callback)
     */
    public CompletableFuture<String> subscribe(
        final String channel,
        final Consumer<String> callback
    ) {
        this.ensurePubSubConnection();
        final String subscriptionId = UUID.randomUUID().toString();
        final SubscriptionEntry entry = new SubscriptionEntry(subscriptionId, callback);

        // Add to subscribers list (create list if needed)
        this.subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(entry);

        // Check if we need to subscribe to Redis (first subscriber for this channel)
        final List<SubscriptionEntry> entries = this.subscribers.get(channel);
        if (entries.size() == 1) {
            // First subscriber - send SUBSCRIBE to Redis
            return this.pubSubAsync.subscribe(channel)
                .toCompletableFuture()
                .orTimeout(5000, TimeUnit.MILLISECONDS)
                .thenApply(v -> subscriptionId)
                .exceptionally(err -> {
                    this.subscribers.get(channel).remove(entry);
                    return subscriptionId;
                });
        }
        // Already subscribed to channel in Redis, just added local callback
        return CompletableFuture.completedFuture(subscriptionId);
    }

    /**
     * Unsubscribe a specific callback from a channel by subscription ID.
     * Only unsubscribes from Redis when last callback is removed.
     *
     * @param channel Channel name
     * @param subscriptionId Subscription ID returned from subscribe()
     * @return Future completing when unsubscribed
     */
    public CompletableFuture<Void> unsubscribe(final String channel, final String subscriptionId) {
        final List<SubscriptionEntry> entries = this.subscribers.get(channel);
        if (entries != null) {
            entries.removeIf(e -> e.id.equals(subscriptionId));

            // Only unsubscribe from Redis when last callback is removed
            if (entries.isEmpty()) {
                this.subscribers.remove(channel);
                if (this.pubSubAsync != null) {
                    return this.pubSubAsync.unsubscribe(channel)
                        .toCompletableFuture()
                        .orTimeout(1000, TimeUnit.MILLISECONDS)
                        .thenApply(v -> (Void) null)
                        .exceptionally(err -> null);
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Unsubscribe all callbacks from a channel.
     *
     * @param channel Channel name to unsubscribe from
     * @return Future completing when unsubscribed
     */
    public CompletableFuture<Void> unsubscribe(final String channel) {
        this.subscribers.remove(channel);
        if (this.pubSubAsync != null) {
            return this.pubSubAsync.unsubscribe(channel)
                .toCompletableFuture()
                .orTimeout(1000, TimeUnit.MILLISECONDS)
                .thenApply(v -> (Void) null)
                .exceptionally(err -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Publish a message to a channel.
     * Uses the main connection (not pub/sub connection) for publishing.
     *
     * @param channel Channel name to publish to
     * @param message Message to publish
     * @return Future completing when published
     */
    public CompletableFuture<Long> publish(final String channel, final String message) {
        return this.async.publish(channel, message.getBytes())
            .toCompletableFuture()
            .orTimeout(1000, TimeUnit.MILLISECONDS)
            .exceptionally(err -> 0L);
    }

    /**
     * Ensure pub/sub connection is initialized (lazy initialization).
     * Thread-safe using double-checked locking.
     */
    private void ensurePubSubConnection() {
        if (this.pubSubConnection == null) {
            synchronized (this) {
                if (this.pubSubConnection == null) {
                    this.pubSubConnection = this.client.connectPubSub();
                    this.pubSubAsync = this.pubSubConnection.async();
                    // Add listener to dispatch messages to all subscribers for a channel
                    this.pubSubConnection.addListener(new RedisPubSubListener<String, String>() {
                        @Override
                        public void message(final String channel, final String message) {
                            final List<SubscriptionEntry> entries =
                                ValkeyConnection.this.subscribers.get(channel);
                            if (entries != null) {
                                for (final SubscriptionEntry entry : entries) {
                                    try {
                                        entry.callback.accept(message);
                                    } catch (final Exception ignored) {
                                        // Don't let one callback failure stop others
                                    }
                                }
                            }
                        }

                        @Override
                        public void message(
                            final String pattern,
                            final String channel,
                            final String message
                        ) {
                            // Pattern subscriptions not used
                        }

                        @Override
                        public void subscribed(final String channel, final long count) {
                            // Subscription confirmed
                        }

                        @Override
                        public void psubscribed(final String pattern, final long count) {
                            // Pattern subscription confirmed
                        }

                        @Override
                        public void unsubscribed(final String channel, final long count) {
                            // Unsubscription confirmed
                        }

                        @Override
                        public void punsubscribed(final String pattern, final long count) {
                            // Pattern unsubscription confirmed
                        }
                    });
                }
            }
        }
    }

    @Override
    public void close() {
        this.subscribers.clear();
        if (this.pubSubConnection != null) {
            this.pubSubConnection.close();
        }
        this.connection.close();
        this.client.shutdown();
    }

    /**
     * Subscription entry holding callback and its unique ID.
     */
    private static final class SubscriptionEntry {
        /**
         * Unique subscription ID.
         */
        private final String id;

        /**
         * Callback to invoke on message.
         */
        private final Consumer<String> callback;

        SubscriptionEntry(final String id, final Consumer<String> callback) {
            this.id = id;
            this.callback = callback;
        }
    }
}
