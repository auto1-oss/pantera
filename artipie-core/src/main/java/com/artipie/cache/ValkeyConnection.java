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
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    @Override
    public void close() {
        this.connection.close();
        this.client.shutdown();
    }
}
