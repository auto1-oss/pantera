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
package com.auto1.pantera.auth;

import com.auto1.pantera.db.dao.AuthProviderDao;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.log.EcsLogger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * Authentication decorator that honors the {@code auth_providers} table's
 * {@code enabled} flag at runtime.
 *
 * <p>The original {@link com.auto1.pantera.settings.YamlSettings#initAuth}
 * builds the chain from YAML {@code meta.credentials} once at startup and
 * never consults the database again. Admins who toggled or deleted a
 * provider via the UI observed that the chain still used the old state:
 * disabling Keycloak did not stop a Keycloak login attempt from
 * succeeding. This class closes that gap by re-checking the DB before
 * each authentication call, with a short per-JVM cache to avoid hammering
 * the database on every request.</p>
 *
 * <p>Wrap each provider added to the chain with this class, tagged with
 * its {@code type} (the key used in the {@code auth_providers} table).
 * When the provider's row is disabled or deleted, {@link #canHandle},
 * {@link #user}, and {@link #isAuthoritative} all short-circuit to "not
 * enabled" behavior so the Joined chain moves on.</p>
 *
 * @since 2.1.0
 */
public final class DbGatedAuth implements Authentication {

    /**
     * Inner authentication provider being gated.
     */
    private final Authentication inner;

    /**
     * {@code auth_providers.type} key this wrapper is bound to.
     */
    private final String type;

    /**
     * Shared enabled-types snapshot across all DbGatedAuth instances.
     * Using a single atomic reference means one DB query can serve
     * every provider in the chain per refresh window.
     */
    private final EnabledTypesCache cache;

    /**
     * Ctor.
     *
     * @param inner Inner authentication provider
     * @param type Provider type as stored in auth_providers.type
     * @param cache Shared enabled-types cache
     */
    public DbGatedAuth(final Authentication inner, final String type,
        final EnabledTypesCache cache) {
        this.inner = inner;
        this.type = type;
        this.cache = cache;
    }

    @Override
    public Optional<AuthUser> user(final String name, final String pass) {
        if (!this.cache.isEnabled(this.type)) {
            return Optional.empty();
        }
        return this.inner.user(name, pass);
    }

    @Override
    public boolean canHandle(final String username) {
        return this.cache.isEnabled(this.type) && this.inner.canHandle(username);
    }

    @Override
    public Collection<String> userDomains() {
        return this.inner.userDomains();
    }

    @Override
    public boolean isAuthoritative(final String username) {
        return this.cache.isEnabled(this.type) && this.inner.isAuthoritative(username);
    }

    @Override
    public String toString() {
        return String.format("DbGatedAuth(type=%s, inner=%s)",
            this.type, this.inner);
    }

    /**
     * Shared cache for enabled auth provider types.
     *
     * <p>One instance is constructed at startup and passed to every
     * {@link DbGatedAuth} wrapper in the chain. It snapshots the
     * {@code auth_providers} table's enabled types with a short TTL
     * (default 5 seconds) so admin toggle/delete actions take effect
     * within seconds without requiring a server restart.</p>
     */
    public static final class EnabledTypesCache {

        /**
         * Cache TTL in milliseconds.
         */
        private static final long TTL_MS = 5_000L;

        /**
         * Datasource for the lookup query.
         */
        private final DataSource source;

        /**
         * Shared snapshot: enabled types + when it was computed.
         */
        private final AtomicReference<Snapshot> snapshot;

        /**
         * Ctor.
         *
         * @param source DataSource
         */
        public EnabledTypesCache(final DataSource source) {
            this.source = source;
            this.snapshot = new AtomicReference<>(null);
        }

        /**
         * Check whether the given provider type is currently enabled.
         * Refreshes the cache if the TTL has elapsed.
         *
         * @param type Provider type
         * @return True if enabled, false if disabled or deleted
         */
        public boolean isEnabled(final String type) {
            final Snapshot current = this.snapshot.get();
            if (current == null
                || System.currentTimeMillis() - current.createdAtMs > TTL_MS) {
                refresh();
            }
            final Snapshot fresh = this.snapshot.get();
            return fresh != null && fresh.enabledTypes.contains(type);
        }

        /**
         * Force a refresh of the enabled-types snapshot from the DB.
         * Called by the TTL path in {@link #isEnabled} and can also be
         * invoked directly after a UI-driven provider change to make
         * the next request see the update immediately.
         */
        public void refresh() {
            final Set<String> types = new HashSet<>();
            try (Connection conn = this.source.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                    "SELECT type FROM auth_providers WHERE enabled = TRUE"
                )) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        types.add(rs.getString(1));
                    }
                }
                this.snapshot.set(new Snapshot(types, System.currentTimeMillis()));
            } catch (final Exception ex) {
                EcsLogger.warn("com.auto1.pantera.auth")
                    .message("Failed to refresh enabled auth provider types "
                        + "— falling back to previous snapshot")
                    .eventCategory("authentication")
                    .eventAction("enabled_types_refresh")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
                // Keep the previous snapshot rather than going to no-providers.
                if (this.snapshot.get() == null) {
                    this.snapshot.set(
                        new Snapshot(types, System.currentTimeMillis())
                    );
                }
            }
        }

        /**
         * Immutable snapshot of enabled types at a point in time.
         */
        private static final class Snapshot {
            private final Set<String> enabledTypes;
            private final long createdAtMs;

            Snapshot(final Set<String> enabledTypes, final long createdAtMs) {
                this.enabledTypes = Set.copyOf(enabledTypes);
                this.createdAtMs = createdAtMs;
            }
        }
    }
}
