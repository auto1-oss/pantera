/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.lock.storage;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scheduled cleanup of expired lock proposals from storage.
 * <p>
 * Periodically scans the {@code .artipie-locks/} prefix in storage, reads each
 * proposal value, and deletes proposals whose expiration timestamp has passed.
 * Proposals with no expiration (empty content) are left untouched.
 * </p>
 *
 * @since 1.20.13
 */
public final class LockCleanupScheduler implements AutoCloseable {

    /**
     * Logger.
     */
    private static final Logger LOGGER =
        Logger.getLogger(LockCleanupScheduler.class.getName());

    /**
     * Root prefix for all lock proposals in storage.
     */
    private static final Key LOCKS_ROOT = new Key.From(".pantera-locks");

    /**
     * Default cleanup interval in seconds.
     */
    private static final long DEFAULT_INTERVAL = 60L;

    /**
     * Storage to scan for expired proposals.
     */
    private final Storage storage;

    /**
     * Scheduled executor for periodic cleanup.
     */
    private final ScheduledExecutorService scheduler;

    /**
     * Cleanup interval in seconds.
     */
    private final long interval;

    /**
     * Ctor with default 60-second interval.
     *
     * @param storage Storage.
     */
    public LockCleanupScheduler(final Storage storage) {
        this(storage, LockCleanupScheduler.DEFAULT_INTERVAL);
    }

    /**
     * Ctor.
     *
     * @param storage Storage.
     * @param interval Cleanup interval in seconds.
     */
    public LockCleanupScheduler(final Storage storage, final long interval) {
        this.storage = storage;
        this.interval = interval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "lock-cleanup");
                thread.setDaemon(true);
                return thread;
            }
        );
    }

    /**
     * Start the periodic cleanup schedule.
     */
    public void start() {
        this.scheduler.scheduleAtFixedRate(
            this::cleanup,
            this.interval,
            this.interval,
            TimeUnit.SECONDS
        );
    }

    /**
     * Run a single cleanup pass. Exposed for testing.
     *
     * @return Completion of cleanup.
     */
    public CompletableFuture<Void> runOnce() {
        return this.doCleanup();
    }

    @Override
    public void close() {
        this.scheduler.shutdownNow();
    }

    /**
     * Cleanup task executed by the scheduler.
     */
    private void cleanup() {
        try {
            this.doCleanup().join();
        } catch (final Exception ex) {
            LOGGER.log(Level.WARNING, "Lock cleanup failed", ex);
        }
    }

    /**
     * Perform the actual cleanup: list all keys under {@code .artipie-locks/},
     * read each value, and delete any whose expiration timestamp is in the past.
     *
     * @return Completion of cleanup.
     */
    private CompletableFuture<Void> doCleanup() {
        final Instant now = Instant.now();
        return this.storage.list(LockCleanupScheduler.LOCKS_ROOT)
            .thenCompose(
                keys -> CompletableFuture.allOf(
                    keys.stream()
                        .map(
                            key -> this.storage.value(key)
                                .thenCompose(content -> content.asStringFuture())
                                .thenCompose(
                                    expiration -> {
                                        if (!expiration.isEmpty()
                                            && !Instant.parse(expiration).isAfter(now)) {
                                            LOGGER.log(
                                                Level.FINE,
                                                "Deleting expired lock proposal: {0}",
                                                key
                                            );
                                            return this.storage.delete(key);
                                        }
                                        return CompletableFuture.allOf();
                                    }
                                )
                                .exceptionally(
                                    throwable -> {
                                        LOGGER.log(
                                            Level.FINE,
                                            String.format(
                                                "Skipping proposal key %s during cleanup", key
                                            ),
                                            throwable
                                        );
                                        return null;
                                    }
                                )
                                .toCompletableFuture()
                        )
                        .toArray(CompletableFuture[]::new)
                )
            )
            .exceptionally(
                throwable -> {
                    LOGGER.log(
                        Level.FINE,
                        "No lock proposals found or listing failed",
                        throwable
                    );
                    return null;
                }
            );
    }
}
