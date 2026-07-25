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
package com.auto1.pantera.maven.security;

import java.util.Optional;

/**
 * Process-wide holder for the active {@link KeyringStore} (WS4-maven.1).
 *
 * <p>Mirrors the {@code install}/{@code installed}/{@code activeSupplier}
 * lifecycle used by {@code UpstreamBreakerSettingsLoader} (pantera-main):
 * {@code VertxMain} installs a {@link JdbcKeyringStore} backed by the
 * shared {@code DataSource} once, after Flyway runs; DB-less boots (tests,
 * embedded) never install anything and {@link #active()} degrades to an
 * always-empty store — {@code verifyPgp: true} then rejects every signed
 * artifact (fail-closed, never fail-open), which is the documented
 * behaviour for "enabled without any uploaded keys".
 *
 * <p>Lives in {@code maven-adapter} (not {@code pantera-main}) because
 * {@code maven-adapter} does not depend on {@code pantera-main} — see
 * {@code WS4-maven.md} §2's module-direction note — while
 * {@code pantera-main} already depends on {@code maven-adapter}.
 *
 * @since 2.3.0
 */
public final class KeyringStoreRegistry {

    /** Empty store — every lookup misses. Used before {@link #install} runs. */
    private static final KeyringStore EMPTY = keyId -> Optional.empty();

    /** The installed store, or {@code null} before boot wiring runs. */
    private static volatile KeyringStore installed;

    private KeyringStoreRegistry() {
    }

    /**
     * Install the shared store. Idempotent; a later call replaces the
     * earlier one (used by tests to swap in a fixture store).
     *
     * @param store Store to install
     */
    public static void install(final KeyringStore store) {
        installed = store;
    }

    /** Clear the installed store (tests, shutdown). */
    public static void uninstall() {
        installed = null;
    }

    /**
     * @return The installed store, or an always-empty store when none has
     *         been installed (DB-less boot).
     */
    public static KeyringStore active() {
        final KeyringStore current = installed;
        return current != null ? current : EMPTY;
    }

    /**
     * Evict a single key from the installed store's cache, when the
     * installed store is a {@link JdbcKeyringStore} (a no-op otherwise —
     * e.g. the DB-less {@link #EMPTY} store, or a test fixture with no
     * cache to invalidate). Called by the admin keyring endpoint after an
     * upload/delete so the next verification picks up the change without
     * waiting for the store's own TTL.
     *
     * @param keyId Long key id (signed 64-bit form)
     */
    public static void invalidate(final long keyId) {
        if (installed instanceof JdbcKeyringStore jdbc) {
            jdbc.invalidate(keyId);
        }
    }
}
