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

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import com.auto1.pantera.http.log.EcsLogger;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * Long-lived LISTEN connection that delivers {@code settings_changed}
 * NOTIFY payloads (emitted by the V127 trigger) to a
 * {@link SettingsChangeListener} callback.
 *
 * <p>Owns a single daemon thread named {@code pantera-settings-listener}
 * that holds one JDBC connection from the pool indefinitely. On connection
 * loss the loop sleeps for {@value #LISTEN_BACKOFF_MS}ms and reconnects.
 *
 * @since 2.2.0
 */
public final class PgListenNotify {

    /** Polling interval (ms) for {@link PGConnection#getNotifications(int)}. */
    private static final long POLL_INTERVAL_MS = 200L;

    /** Backoff (ms) before reconnecting after a connection failure. */
    private static final int LISTEN_BACKOFF_MS = 5_000;

    private final DataSource source;
    private final SettingsChangeListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch listening = new CountDownLatch(1);
    private volatile Thread thread;

    public PgListenNotify(final DataSource source, final SettingsChangeListener listener) {
        this.source = source;
        this.listener = listener;
    }

    /**
     * Starts the LISTEN worker thread. Idempotent: subsequent calls while
     * already running are no-ops.
     */
    public void start() {
        if (!this.running.compareAndSet(false, true)) {
            return;
        }
        this.thread = new Thread(this::loop, "pantera-settings-listener");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * Signals the worker to stop. The worker thread observes the running flag at
     * the next poll boundary, so shutdown latency is bounded by
     * {@value #POLL_INTERVAL_MS} ms during normal operation. The thread interrupt
     * is needed only to wake the {@value #LISTEN_BACKOFF_MS} ms reconnect backoff
     * if a connection failure is in progress; JDBC socket reads do not honour
     * {@link Thread#interrupt()} directly.
     */
    public void stop() {
        this.running.set(false);
        if (this.thread != null) {
            this.thread.interrupt();
        }
    }

    /**
     * Blocks the calling thread until {@code LISTEN settings_changed} has been
     * issued on the worker connection (or the timeout elapses).
     * Returns true if listening is active, false if the timeout was reached.
     *
     * <p>Useful for tests that need a deterministic happens-before between
     * worker startup and the first NOTIFY.
     */
    public boolean awaitListening(final Duration timeout) throws InterruptedException {
        return this.listening.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void loop() {
        while (this.running.get()) {
            try (Connection conn = this.source.getConnection()) {
                try (Statement st = conn.createStatement()) {
                    st.execute("LISTEN settings_changed");
                }
                this.listening.countDown();
                final PGConnection pg = conn.unwrap(PGConnection.class);
                while (this.running.get()) {
                    final PGNotification[] notifications =
                        pg.getNotifications((int) POLL_INTERVAL_MS);
                    if (notifications != null) {
                        for (final PGNotification n : notifications) {
                            dispatch(n);
                        }
                    }
                }
            } catch (final Exception ex) {
                if (!this.running.get()) {
                    return;
                }
                EcsLogger.warn("com.auto1.pantera.settings.runtime")
                    .message("LISTEN connection lost; reconnecting in 5s")
                    .field("error.message", ex.getMessage())
                    .log();
                try {
                    Thread.sleep(LISTEN_BACKOFF_MS);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void dispatch(final PGNotification n) {
        try {
            this.listener.onChanged(n.getParameter());
        } catch (final Throwable t) {
            EcsLogger.warn("com.auto1.pantera.settings.runtime")
                .message("Settings change listener threw")
                .field("settings.key", n.getParameter())
                .field("error.message", t.getMessage())
                .log();
        }
    }
}
