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
    private Thread thread;

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
     * Signals the worker to stop and interrupts it. Safe to call multiple
     * times. Does not join the thread.
     */
    public void stop() {
        this.running.set(false);
        if (this.thread != null) {
            this.thread.interrupt();
        }
    }

    private void loop() {
        while (this.running.get()) {
            try (Connection conn = this.source.getConnection()) {
                try (Statement st = conn.createStatement()) {
                    st.execute("LISTEN settings_changed");
                }
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
