/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.artipie.http.log.EcsLogger;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.support.ConnectionPoolSupport;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

/**
 * Valkey/Redis connection pool for L2 cache across Artipie.
 * Uses Lettuce's built-in connection pooling backed by Apache Commons Pool2.
 * Thread-safe, async operations with round-robin connection selection.
 *
 * @since 1.0
 */
public final class ValkeyConnection implements AutoCloseable {

    /**
     * Default maximum total connections in the pool.
     */
    private static final int DEFAULT_MAX_TOTAL = 8;

    /**
     * Default maximum idle connections.
     */
    private static final int DEFAULT_MAX_IDLE = 4;

    /**
     * Default minimum idle connections.
     */
    private static final int DEFAULT_MIN_IDLE = 2;

    /**
     * Redis client.
     */
    private final RedisClient client;

    /**
     * Connection pool.
     */
    private final GenericObjectPool<StatefulRedisConnection<String, byte[]>> pool;

    /**
     * Pre-borrowed connections for round-robin async access.
     * These connections stay borrowed for the lifetime of ValkeyConnection.
     */
    private final StatefulRedisConnection<String, byte[]>[] connections;

    /**
     * Async command interfaces corresponding to each connection.
     */
    private final RedisAsyncCommands<String, byte[]>[] asyncCommands;

    /**
     * Round-robin index for connection selection.
     */
    private final AtomicInteger index;

    /**
     * Number of active (pre-borrowed) connections.
     */
    private final int poolSize;

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
     * Constructor with explicit parameters and default pool size.
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
        this(host, port, timeout, ValkeyConnection.DEFAULT_MAX_TOTAL);
    }

    /**
     * Constructor with explicit parameters and custom pool size.
     *
     * @param host Valkey/Redis host
     * @param port Valkey/Redis port
     * @param timeout Request timeout
     * @param size Number of connections in the pool
     */
    @SuppressWarnings("unchecked")
    public ValkeyConnection(
        final String host,
        final int port,
        final Duration timeout,
        final int size
    ) {
        this.client = RedisClient.create(
            RedisURI.builder()
                .withHost(Objects.requireNonNull(host))
                .withPort(port)
                .withTimeout(timeout)
                .build()
        );
        final RedisCodec<String, byte[]> codec = RedisCodec.of(
            StringCodec.UTF8,
            ByteArrayCodec.INSTANCE
        );
        final GenericObjectPoolConfig<StatefulRedisConnection<String, byte[]>> config =
            new GenericObjectPoolConfig<>();
        config.setMaxTotal(Math.max(size, ValkeyConnection.DEFAULT_MIN_IDLE));
        config.setMaxIdle(Math.min(ValkeyConnection.DEFAULT_MAX_IDLE, size));
        config.setMinIdle(ValkeyConnection.DEFAULT_MIN_IDLE);
        config.setTestOnBorrow(true);
        this.pool = ConnectionPoolSupport.createGenericObjectPool(
            () -> this.client.connect(codec),
            config
        );
        this.poolSize = Math.max(size, ValkeyConnection.DEFAULT_MIN_IDLE);
        this.connections = new StatefulRedisConnection[this.poolSize];
        this.asyncCommands = new RedisAsyncCommands[this.poolSize];
        this.index = new AtomicInteger(0);
        this.initConnections();
    }

    /**
     * Get async commands interface.
     * Returns commands from a pool connection using round-robin selection,
     * distributing load across multiple connections.
     *
     * @return Redis async commands
     */
    public RedisAsyncCommands<String, byte[]> async() {
        final int idx = Math.abs(this.index.getAndIncrement() % this.poolSize);
        return this.asyncCommands[idx];
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
            return "PONG".equals(this.async().ping().get());
        } catch (final Exception ex) {
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
        return this.async().ping()
            .toCompletableFuture()
            .orTimeout(1000, TimeUnit.MILLISECONDS)
            .thenApply(pong -> "PONG".equals(pong))
            .exceptionally(err -> false);
    }

    /**
     * Returns the number of connections in the pool.
     *
     * @return Pool size
     */
    public int poolSize() {
        return this.poolSize;
    }

    /**
     * Create a new pub/sub connection for subscribe/publish operations.
     * Uses String codec for both keys and values (pub/sub channels are text).
     * <p>
     * The caller is responsible for closing the returned connection.
     *
     * @return New pub/sub connection
     * @since 1.20.13
     */
    public StatefulRedisPubSubConnection<String, String> connectPubSub() {
        return this.client.connectPubSub();
    }

    @Override
    public void close() {
        for (int idx = 0; idx < this.poolSize; idx += 1) {
            if (this.connections[idx] != null) {
                try {
                    this.pool.returnObject(this.connections[idx]);
                } catch (final Exception ex) {
                    EcsLogger.debug("com.artipie.cache")
                        .message("Failed to return connection to pool during close")
                        .error(ex)
                        .log();
                }
            }
        }
        this.pool.close();
        this.client.shutdown();
    }

    /**
     * Pre-borrow connections from the pool and set up async command interfaces.
     */
    private void initConnections() {
        for (int idx = 0; idx < this.poolSize; idx += 1) {
            try {
                this.connections[idx] = this.pool.borrowObject();
                this.asyncCommands[idx] = this.connections[idx].async();
                this.asyncCommands[idx].setAutoFlushCommands(true);
            } catch (final Exception ex) {
                throw new IllegalStateException(
                    String.format(
                        "Failed to initialize connection %d of %d in Valkey pool",
                        idx + 1, this.poolSize
                    ),
                    ex
                );
            }
        }
    }
}
