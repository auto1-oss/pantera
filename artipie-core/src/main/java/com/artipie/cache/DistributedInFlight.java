/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.artipie.metrics.MicrometerMetrics;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Distributed in-flight request tracking for request deduplication.
 * Prevents thundering herd by ensuring only one node fetches an uncached asset
 * while others wait. In cluster mode uses Valkey/Redis for coordination,
 * falls back to local ConcurrentHashMap for single-node deployments.
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * DistributedInFlight inFlight = new DistributedInFlight("npm-proxy", Duration.ofSeconds(90));
 *
 * // Check if already in flight
 * InFlightResult result = inFlight.tryAcquire(key).join();
 * if (result.isWaiter()) {
 *     // Wait for leader to complete, then read from cache
 *     return result.waitForLeader().thenCompose(success -> readFromCache(key));
 * }
 *
 * // We are the leader - do the fetch
 * try {
 *     Response response = fetchFromUpstream(key).join();
 *     result.complete(true);
 *     return response;
 * } catch (Exception e) {
 *     result.complete(false);
 *     throw e;
 * }
 * }</pre>
 *
 * @since 1.0
 */
public final class DistributedInFlight {

    /**
     * Lock key prefix in Valkey.
     */
    private static final String LOCK_PREFIX = "inflight:";

    /**
     * Channel prefix for pub/sub notifications when request completes.
     */
    private static final String DONE_CHANNEL_PREFIX = "inflight:done:";

    /**
     * Repository/adapter name for key namespacing.
     */
    private final String namespace;

    /**
     * Lock timeout duration.
     */
    private final Duration timeout;

    /**
     * Local in-flight map for single-node mode or Valkey fallback.
     */
    private final Map<String, LocalEntry> localInFlight;

    /**
     * Unique node identifier for distributed locking.
     */
    private final String nodeId;

    /**
     * Valkey connection (optional - uses local map if not available).
     */
    private final Optional<ValkeyConnection> valkey;

    /**
     * Constructor with default timeout (90 seconds).
     *
     * @param namespace Repository/adapter name for key namespacing
     */
    public DistributedInFlight(final String namespace) {
        this(namespace, Duration.ofSeconds(90));
    }

    /**
     * Constructor with custom timeout.
     *
     * @param namespace Repository/adapter name for key namespacing
     * @param timeout Lock timeout duration
     */
    public DistributedInFlight(final String namespace, final Duration timeout) {
        this.namespace = namespace;
        this.timeout = timeout;
        this.localInFlight = new ConcurrentHashMap<>();
        this.nodeId = UUID.randomUUID().toString();
        this.valkey = GlobalCacheConfig.valkeyConnection();
    }

    /**
     * Constructor with explicit Valkey connection (for testing).
     *
     * @param namespace Repository/adapter name
     * @param timeout Lock timeout
     * @param valkey Valkey connection
     */
    DistributedInFlight(
        final String namespace,
        final Duration timeout,
        final Optional<ValkeyConnection> valkey
    ) {
        this.namespace = namespace;
        this.timeout = timeout;
        this.localInFlight = new ConcurrentHashMap<>();
        this.nodeId = UUID.randomUUID().toString();
        this.valkey = valkey;
    }

    /**
     * Try to acquire leadership for fetching a key.
     * Returns immediately with result indicating if caller is leader or waiter.
     *
     * @param key Asset key to acquire
     * @return Future with InFlightResult
     */
    public CompletableFuture<InFlightResult> tryAcquire(final String key) {
        if (this.valkey.isPresent()) {
            return this.tryAcquireDistributed(key);
        }
        return this.tryAcquireLocal(key);
    }

    /**
     * Release a key after fetch completes.
     * Should be called by leader when fetch succeeds or fails.
     *
     * @param key Asset key to release
     * @param success True if fetch succeeded (asset now in cache)
     * @return Future completing when released
     */
    public CompletableFuture<Void> release(final String key, final boolean success) {
        if (this.valkey.isPresent()) {
            return this.releaseDistributed(key, success);
        }
        return this.releaseLocal(key, success);
    }

    /**
     * Get current number of in-flight requests (for metrics).
     *
     * @return Number of in-flight requests
     */
    public int inFlightCount() {
        return this.localInFlight.size();
    }

    /**
     * Try to acquire leadership using local ConcurrentHashMap.
     * Used in single-node mode or as fallback when Valkey unavailable.
     */
    private CompletableFuture<InFlightResult> tryAcquireLocal(final String key) {
        final String fullKey = this.fullKey(key);
        final CompletableFuture<Boolean> completion = new CompletableFuture<>();
        final LocalEntry newEntry = new LocalEntry(completion, System.currentTimeMillis());

        final LocalEntry existing = this.localInFlight.get(fullKey);
        if (existing != null && !existing.isExpired(this.timeout)) {
            // Another request is already fetching - we are a waiter
            this.recordMetric("acquire", "waiter");
            return CompletableFuture.completedFuture(
                new InFlightResult(false, existing.completion, key, this)
            );
        }

        final LocalEntry prev = this.localInFlight.putIfAbsent(fullKey, newEntry);
        if (prev != null && !prev.isExpired(this.timeout)) {
            // Race condition - another thread added first
            this.recordMetric("acquire", "waiter");
            return CompletableFuture.completedFuture(
                new InFlightResult(false, prev.completion, key, this)
            );
        }

        // Replace expired entry or we won the race
        if (prev != null) {
            // Complete the expired entry's waiters with failure before replacing
            if (!prev.completion.isDone()) {
                prev.completion.complete(false);
            }
            this.localInFlight.put(fullKey, newEntry);
        }

        // We are the leader
        this.recordMetric("acquire", "leader");

        // Set up timeout cleanup
        CompletableFuture.delayedExecutor(this.timeout.toMillis(), TimeUnit.MILLISECONDS)
            .execute(() -> {
                if (!completion.isDone()) {
                    this.localInFlight.remove(fullKey, newEntry);
                    completion.complete(false);
                }
            });

        return CompletableFuture.completedFuture(
            new InFlightResult(true, completion, key, this)
        );
    }

    /**
     * Try to acquire leadership using Valkey distributed lock.
     * Uses SET NX EX for atomic lock acquisition with timeout.
     */
    private CompletableFuture<InFlightResult> tryAcquireDistributed(final String key) {
        final String fullKey = this.fullKey(key);
        final RedisAsyncCommands<String, byte[]> async = this.valkey.orElseThrow().async();
        final byte[] lockValue = this.nodeId.getBytes();

        // Try to acquire distributed lock: SET key value NX EX timeout
        return async.set(
            fullKey,
            lockValue,
            SetArgs.Builder.nx().ex(this.timeout)
        ).toCompletableFuture()
            .thenCompose(result -> {
                if ("OK".equals(result)) {
                    // We got the lock - we are the leader
                    this.recordMetric("acquire", "leader");
                    // Also create local entry for completion tracking
                    final CompletableFuture<Boolean> completion = new CompletableFuture<>();
                    final LocalEntry entry = new LocalEntry(completion, System.currentTimeMillis());
                    this.localInFlight.put(fullKey, entry);

                    return CompletableFuture.completedFuture(
                        new InFlightResult(true, completion, key, this)
                    );
                } else {
                    // Lock exists - we are a waiter
                    this.recordMetric("acquire", "waiter");
                    // Subscribe to Pub/Sub for instant notification when leader completes
                    return this.waitForDistributedRelease(fullKey, key);
                }
            })
            .exceptionallyCompose(err -> {
                // Valkey error - fall back to local (non-blocking)
                return this.tryAcquireLocal(key);
            });
    }

    /**
     * Wait for distributed lock to be released using Pub/Sub.
     * Subscribes to done channel for instant notification when leader completes.
     * Pub/Sub is the only notification mechanism - no polling fallback.
     */
    private CompletableFuture<InFlightResult> waitForDistributedRelease(
        final String fullKey,
        final String key
    ) {
        final CompletableFuture<Boolean> waiterCompletion = new CompletableFuture<>();
        final String doneChannel = DONE_CHANNEL_PREFIX + fullKey;
        final ValkeyConnection connection = this.valkey.orElseThrow();

        // Subscribe to the done channel for instant notification
        connection.subscribe(doneChannel, message -> {
            if (!waiterCompletion.isDone()) {
                // Message format: "success" or "failure"
                final boolean success = "success".equals(message);
                this.recordMetric("wait", success ? "success" : "failure");
                waiterCompletion.complete(success);
            }
        }).thenAccept(subscriptionId -> {
            // Cleanup subscription when completed (using subscription ID)
            waiterCompletion.whenComplete((result, error) ->
                connection.unsubscribe(doneChannel, subscriptionId)
            );

            // After subscribing, check if lock already released (race condition handling)
            // If lock is gone AND we haven't received a pub/sub message yet, it means
            // leader completed before we subscribed. Wait briefly for the message.
            connection.async().get(fullKey).toCompletableFuture()
                .orTimeout(1000, TimeUnit.MILLISECONDS)
                .thenAccept(value -> {
                    if (value == null && !waiterCompletion.isDone()) {
                        // Lock already released before we subscribed
                        // Wait briefly for pub/sub message to arrive
                        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS)
                            .execute(() -> {
                                if (!waiterCompletion.isDone()) {
                                    // If still no pub/sub message after 100ms, assume success
                                    // (leader completed normally, message was sent before subscribe)
                                    waiterCompletion.complete(true);
                                }
                            });
                    }
                })
                .exceptionally(err -> {
                    // Error checking lock status - will rely on pub/sub or timeout
                    return null;
                });
        }).exceptionally(err -> {
            // Pub/Sub subscription failed - fail immediately, no polling fallback
            if (!waiterCompletion.isDone()) {
                waiterCompletion.completeExceptionally(
                    new IllegalStateException("Pub/Sub subscription failed", err)
                );
            }
            return null;
        });

        // Set up timeout - if no notification received within timeout, fail
        CompletableFuture.delayedExecutor(this.timeout.toMillis(), TimeUnit.MILLISECONDS)
            .execute(() -> {
                if (!waiterCompletion.isDone()) {
                    this.recordMetric("wait", "timeout");
                    waiterCompletion.complete(false);
                }
            });

        return CompletableFuture.completedFuture(
            new InFlightResult(false, waiterCompletion, key, this)
        );
    }

    /**
     * Release local entry.
     */
    private CompletableFuture<Void> releaseLocal(final String key, final boolean success) {
        final String fullKey = this.fullKey(key);
        final LocalEntry entry = this.localInFlight.remove(fullKey);
        if (entry != null && !entry.completion.isDone()) {
            entry.completion.complete(success);
        }
        this.recordMetric("release", success ? "success" : "failure");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Release distributed lock and notify waiters via Pub/Sub.
     */
    private CompletableFuture<Void> releaseDistributed(final String key, final boolean success) {
        final String fullKey = this.fullKey(key);
        final String doneChannel = DONE_CHANNEL_PREFIX + fullKey;
        final ValkeyConnection connection = this.valkey.orElseThrow();

        // Release local entry first
        this.releaseLocal(key, success);

        // Delete distributed lock (only if we own it) and publish notification
        final RedisAsyncCommands<String, byte[]> async = connection.async();
        return async.get(fullKey).toCompletableFuture()
            .<Void>thenCompose(value -> {
                if (value != null && this.nodeId.equals(new String(value))) {
                    // We own the lock - delete it and notify waiters
                    return async.del(fullKey).toCompletableFuture()
                        .thenCompose(deleted -> {
                            // Publish completion notification to waiters
                            final String message = success ? "success" : "failure";
                            return connection.publish(doneChannel, message)
                                .thenApply(receivers -> (Void) null);
                        });
                }
                return CompletableFuture.completedFuture(null);
            })
            .exceptionally(err -> null);
    }

    /**
     * Build full key with namespace.
     */
    private String fullKey(final String key) {
        return LOCK_PREFIX + this.namespace + ":" + key;
    }

    /**
     * Record distributed lock metric safely.
     * Only records if metrics are enabled, fails silently otherwise.
     *
     * @param operation Operation: acquire, release, wait
     * @param result Result: leader, waiter, success, failure, timeout
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private void recordMetric(final String operation, final String result) {
        try {
            if (com.artipie.metrics.ArtipieMetrics.isEnabled()
                && MicrometerMetrics.isInitialized()) {
                MicrometerMetrics.getInstance()
                    .recordDistributedLock(this.namespace, operation, result);
            }
        } catch (final Exception ex) {
            // Ignore metric errors - don't fail requests due to metrics
        }
    }

    /**
     * Local entry for tracking in-flight requests.
     */
    private static final class LocalEntry {
        /**
         * Completion future signaled when fetch completes.
         */
        private final CompletableFuture<Boolean> completion;

        /**
         * Timestamp when entry was created.
         */
        private final long createdAt;

        LocalEntry(final CompletableFuture<Boolean> completion, final long createdAt) {
            this.completion = completion;
            this.createdAt = createdAt;
        }

        boolean isExpired(final Duration timeout) {
            return System.currentTimeMillis() - this.createdAt > timeout.toMillis();
        }
    }

    /**
     * Result of tryAcquire operation.
     * Contains information about whether caller is leader or waiter,
     * and provides methods to coordinate completion.
     */
    public static final class InFlightResult {

        /**
         * True if this caller is the leader (should fetch from upstream).
         */
        private final boolean leader;

        /**
         * Completion future - signaled when leader completes.
         */
        private final CompletableFuture<Boolean> completion;

        /**
         * Original key.
         */
        private final String key;

        /**
         * Parent DistributedInFlight for release.
         */
        private final DistributedInFlight parent;

        InFlightResult(
            final boolean leader,
            final CompletableFuture<Boolean> completion,
            final String key,
            final DistributedInFlight parent
        ) {
            this.leader = leader;
            this.completion = completion;
            this.key = key;
            this.parent = parent;
        }

        /**
         * Check if caller is leader (should fetch from upstream).
         *
         * @return True if leader
         */
        public boolean isLeader() {
            return this.leader;
        }

        /**
         * Check if caller is waiter (should wait for leader).
         *
         * @return True if waiter
         */
        public boolean isWaiter() {
            return !this.leader;
        }

        /**
         * Wait for leader to complete fetch.
         * Returns true if leader succeeded (asset in cache), false if failed.
         *
         * @return Future with success status
         */
        public CompletableFuture<Boolean> waitForLeader() {
            return this.completion;
        }

        /**
         * Signal completion (only for leaders).
         * Should be called when fetch completes successfully or fails.
         *
         * @param success True if fetch succeeded and asset is now in cache
         * @return Future completing when release is done
         */
        public CompletableFuture<Void> complete(final boolean success) {
            if (this.leader) {
                return this.parent.release(this.key, success);
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
