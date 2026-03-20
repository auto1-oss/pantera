/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings.repo;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.ConfigFile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class RepoConfigWatcher implements AutoCloseable {

    private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);

    private final Storage storage;

    private final Duration interval;

    private final Runnable listener;

    private final ScheduledExecutorService scheduler;

    private final boolean ownScheduler;

    private final AtomicReference<Map<String, String>> fingerprint;

    private final AtomicBoolean scanning;

    private final AtomicBoolean started;

    private final AtomicBoolean primed;

    private final AtomicBoolean closed;

    private volatile ScheduledFuture<?> task;

    RepoConfigWatcher(
        final Storage storage,
        final Duration interval,
        final Runnable listener,
        final ScheduledExecutorService scheduler,
        final boolean ownScheduler
    ) {
        this.storage = storage;
        this.interval = interval == null ? Duration.ZERO : interval;
        this.listener = listener;
        this.scheduler = scheduler;
        this.ownScheduler = ownScheduler;
        this.fingerprint = new AtomicReference<>(Collections.emptyMap());
        this.scanning = new AtomicBoolean(false);
        this.started = new AtomicBoolean(false);
        this.primed = new AtomicBoolean(false);
        this.closed = new AtomicBoolean(false);
    }

    RepoConfigWatcher(final Storage storage, final Duration interval, final Runnable listener) {
        this(storage, interval, listener, defaultScheduler(), true);
    }

    static RepoConfigWatcher disabled() {
        return new RepoConfigWatcher(null, Duration.ZERO, () -> {}, null, false) {
            @Override
            void start() {
                // no-op
            }

            @Override
            CompletableFuture<Void> runOnce() {
                return COMPLETED;
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }

    void start() {
        if (this.storage == null || this.scheduler == null
            || this.interval.isZero() || this.interval.isNegative()) {
            return;
        }
        if (this.started.compareAndSet(false, true)) {
            this.task = this.scheduler.scheduleWithFixedDelay(
                () -> runOnce(),
                this.interval.toMillis(),
                this.interval.toMillis(),
                TimeUnit.MILLISECONDS
            );
        }
    }

    CompletableFuture<Void> runOnce() {
        if (this.storage == null || this.closed.get()) {
            return COMPLETED;
        }
        if (!this.scanning.compareAndSet(false, true)) {
            return COMPLETED;
        }
        final CompletableFuture<Void> promise = new CompletableFuture<>();
        snapshotFingerprint().whenComplete(
            (fingerprint, error) -> {
                try {
                    if (error != null) {
                        EcsLogger.warn("com.auto1.pantera.settings")
                            .message("Failed to poll repository configs")
                            .eventCategory("configuration")
                            .eventAction("config_watch")
                            .eventOutcome("failure")
                            .error(error)
                            .log();
                    } else {
                        if (!this.primed.getAndSet(true)) {
                            this.fingerprint.set(fingerprint);
                        } else {
                            final Map<String, String> previous = this.fingerprint.get();
                            if (!previous.equals(fingerprint)) {
                                this.fingerprint.set(fingerprint);
                                this.listener.run();
                            }
                        }
                    }
                } finally {
                    this.scanning.set(false);
                    promise.complete(null);
                }
            }
        );
        return promise;
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            Optional.ofNullable(this.task).ifPresent(task -> task.cancel(true));
            if (this.ownScheduler && this.scheduler != null) {
                this.scheduler.shutdownNow();
            }
        }
    }

    private CompletableFuture<Map<String, String>> snapshotFingerprint() {
        return this.storage.list(Key.ROOT)
            .thenCompose(keys -> {
                final var futures = new ArrayList<CompletableFuture<Map.Entry<String, String>>>();
                for (final Key key : keys) {
                    final ConfigFile file = new ConfigFile(key);
                    if (file.isSystem() || !file.isYamlOrYml()) {
                        continue;
                    }
                    futures.add(
                        this.storage.value(key)
                            .thenCompose(content -> content.asStringFuture())
                            .thenApply(content -> Map.entry(key.string(), contentHash(content)))
                            .exceptionally(err -> Map.entry(key.string(), "error"))
                    );
                }
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(
                        ignored -> {
                            final Map<String, String> result = new TreeMap<>();
                            for (final CompletableFuture<Map.Entry<String, String>> future : futures) {
                                final Map.Entry<String, String> entry = future.join();
                                result.put(entry.getKey(), entry.getValue());
                            }
                            return result;
                        }
                    );
            });
    }

    private static String contentHash(final String content) {
        try {
            final java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            final byte[] digest = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (final byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final java.security.NoSuchAlgorithmException ex) {
            return String.valueOf(content.hashCode());
        }
    }

    private static ScheduledExecutorService defaultScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "pantera.repo.watcher");
                thread.setDaemon(true);
                return thread;
            }
        );
    }
}
