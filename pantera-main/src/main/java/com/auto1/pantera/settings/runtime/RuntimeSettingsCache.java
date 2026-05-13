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
package com.auto1.pantera.settings.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.json.JsonObject;
import javax.sql.DataSource;
import com.auto1.pantera.db.dao.SettingsDao;
import com.auto1.pantera.http.log.EcsLogger;

/**
 * Hot-reloaded snapshot of all runtime-tunable settings.
 *
 * <p>Reads are lock-free (volatile field reads). Writes go through
 * {@link SettingsDao#put}; the resulting NOTIFY (V127 trigger) wakes
 * {@link PgListenNotify}, which triggers a re-read and atomic snapshot swap.
 *
 * <p>A 30-second polling fallback covers the case where LISTEN is lost
 * (e.g., transient connection drop). Uses {@link SettingsDao#getChangedSince}.
 *
 * @since 2.2.0
 */
public final class RuntimeSettingsCache {

    /** Polling-fallback interval (seconds) used when LISTEN is lost. */
    private static final long POLL_FALLBACK_SECONDS = 30L;

    private final SettingsDao dao;
    private final PgListenNotify listener;
    private final Map<String, List<SettingsChangeListener>> subscribers =
        new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller =
        Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "pantera-settings-poller");
            t.setDaemon(true);
            return t;
        });

    private volatile Snapshot snapshot = Snapshot.defaults();
    private volatile Instant lastReadAt = Instant.EPOCH;

    private record Snapshot(
        HttpTuning http,
        Map<String, JsonObject> raw
    ) {
        static Snapshot defaults() {
            return new Snapshot(
                HttpTuning.defaults(),
                Map.of()
            );
        }
    }

    public RuntimeSettingsCache(final SettingsDao dao, final DataSource listenSource) {
        this.dao = dao;
        this.listener = new PgListenNotify(listenSource, this::onKeyChanged);
    }

    /**
     * Performs an initial blocking re-read so the first snapshot is fresh,
     * then starts the LISTEN worker and the 30-second polling fallback.
     */
    public void start() {
        rereadAll();
        this.listener.start();
        this.poller.scheduleAtFixedRate(this::pollFallback,
            POLL_FALLBACK_SECONDS, POLL_FALLBACK_SECONDS, TimeUnit.SECONDS);
        EcsLogger.info("com.auto1.pantera.settings.runtime")
            .message("RuntimeSettingsCache started")
            .eventCategory("process")
            .eventAction("settings_cache_start")
            .eventOutcome("success")
            .field("settings.keys", this.snapshot.raw().size())
            .log();
    }

    /** Stops the polling fallback and the LISTEN worker. */
    public void stop() {
        this.poller.shutdownNow();
        try {
            this.poller.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        this.listener.stop();
    }

    public HttpTuning httpTuning() {
        return this.snapshot.http();
    }

    public Map<String, JsonObject> raw() {
        return this.snapshot.raw();
    }

    /**
     * Subscribes {@code cb} to all key changes whose key starts with
     * {@code keyPrefix}. Multiple subscribers per prefix are supported.
     */
    public void addListener(final String keyPrefix, final SettingsChangeListener cb) {
        this.subscribers
            .computeIfAbsent(keyPrefix, k -> new CopyOnWriteArrayList<>())
            .add(cb);
    }

    /**
     * Blocks the calling thread until the underlying LISTEN/NOTIFY connection
     * has issued LISTEN at least once, or the timeout elapses. Useful for
     * tests that need a deterministic happens-before between cache start
     * and the first NOTIFY.
     *
     * @param timeout maximum time to wait
     * @return true if listening is active, false on timeout
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean awaitListening(final Duration timeout) throws InterruptedException {
        return this.listener.awaitListening(timeout);
    }

    private void onKeyChanged(final String key) {
        rereadAll();
        this.subscribers.forEach((prefix, listeners) -> {
            if (key.startsWith(prefix)) {
                for (final SettingsChangeListener l : listeners) {
                    try {
                        l.onChanged(key);
                    } catch (final Throwable t) {
                        EcsLogger.warn("com.auto1.pantera.settings.runtime")
                            .message("Subscriber for " + prefix + " threw")
                            .field("error.message", t.getMessage())
                            .log();
                    }
                }
            }
        });
    }

    private synchronized void rereadAll() {
        final Instant now = Instant.now();
        final Map<String, JsonObject> rows = this.dao.listAll();
        this.snapshot = new Snapshot(
            HttpTuning.fromMap(rows),
            Map.copyOf(rows)
        );
        this.lastReadAt = now;
    }

    private void pollFallback() {
        try {
            final Map<String, JsonObject> changed = this.dao.getChangedSince(this.lastReadAt);
            if (!changed.isEmpty()) {
                EcsLogger.warn("com.auto1.pantera.settings.runtime")
                    .message("Polling fallback detected " + changed.size()
                        + " missed changes; reloading")
                    .log();
                changed.keySet().forEach(this::onKeyChanged);
            }
        } catch (final Throwable t) {
            EcsLogger.warn("com.auto1.pantera.settings.runtime")
                .message("Settings poll failed: " + t.getMessage()).log();
        }
    }
}
