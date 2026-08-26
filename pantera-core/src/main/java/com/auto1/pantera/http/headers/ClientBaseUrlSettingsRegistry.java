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
package com.auto1.pantera.http.headers;

import java.util.function.Supplier;

/**
 * Process-wide static holder for the REAL {@link ClientBaseUrlSettings}
 * supplier, mirroring the {@code UpstreamBreakerSettingsLoader}/{@code
 * StorageInvalidationBusRegistry} static-install pattern this codebase
 * already uses for cross-module wiring that a lower module's constructor
 * signature cannot carry directly.
 *
 * <p><strong>Why a registry instead of a constructor argument:</strong>
 * {@link ClientBaseUrl} lives in {@code pantera-core}, which cannot depend
 * on {@code pantera-main} (where the DB-backed loader and its {@code
 * AuthSettingsDao} live), and {@link ClientBaseUrl} is constructed ad hoc
 * across several modules (this one, {@code pantera-main}, {@code
 * npm-adapter}) rather than assembled once at boot. Instead, {@code
 * pantera-main}'s {@code VertxMain} calls {@link #install(Supplier)} once
 * at boot with a supplier backed by its DB-aware loader, and {@link
 * ClientBaseUrl}'s single-argument constructor reads {@link #active()} on
 * every construction -- i.e. on every request -- so a change applied via
 * the admin API and broadcast to every node is picked up on the very next
 * request, with no restart.</p>
 *
 * <p>A DB-less production boot still calls {@link #install} -- {@code
 * VertxMain} installs the loader unconditionally, passing a {@code null}
 * DAO when no shared {@code DataSource} is configured -- so the env-var
 * fallback tier keeps resolving exactly as it did before this setting
 * became DB-backed. Only a caller that never reaches that {@code VertxMain}
 * wiring at all (most unit tests, or a hand-built {@link ClientBaseUrl} in
 * isolation) sees {@link #active()} fall all the way back to {@link
 * ClientBaseUrlSettings#defaults()}.</p>
 *
 * @since 2.3.0
 */
public final class ClientBaseUrlSettingsRegistry {

    /**
     * Installed supplier, or {@code null} if none (single-instance / DB-less
     * boot, or any unit test that never installs one).
     */
    private static volatile Supplier<ClientBaseUrlSettings> installed;

    private ClientBaseUrlSettingsRegistry() {
    }

    /**
     * Install the process-wide supplier. Idempotent -- a later call replaces
     * an earlier one.
     *
     * @param supplier Supplier resolving the current settings on every call.
     */
    public static void install(final Supplier<ClientBaseUrlSettings> supplier) {
        ClientBaseUrlSettingsRegistry.installed = supplier;
    }

    /**
     * Clear the installed supplier (tests, shutdown).
     */
    public static void uninstall() {
        ClientBaseUrlSettingsRegistry.installed = null;
    }

    /**
     * The active settings: resolved from the installed supplier, or {@link
     * ClientBaseUrlSettings#defaults()} if none was installed. Safe to call
     * before {@link #install}. Re-resolves on every call -- callers get the
     * current value, never one cached at install time.
     *
     * @return Active settings, never {@code null}.
     */
    public static ClientBaseUrlSettings active() {
        final Supplier<ClientBaseUrlSettings> current = ClientBaseUrlSettingsRegistry.installed;
        return current == null ? ClientBaseUrlSettings.defaults() : current.get();
    }
}
