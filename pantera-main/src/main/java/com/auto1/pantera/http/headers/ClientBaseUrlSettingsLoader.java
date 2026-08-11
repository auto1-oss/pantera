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

import com.auto1.pantera.db.dao.AuthSettingsDao;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Loads {@link ClientBaseUrlSettings} — {@code trustForwardedHeaders},
 * {@code hostAllowlist}, and {@code canonicalBaseUrl}, all consumed by
 * {@code pantera-core}'s {@link ClientBaseUrl} — from the DB with env-var
 * and hardcoded fallbacks. Mirrors {@code UpstreamBreakerSettingsLoader}
 * exactly.
 *
 * <p>Load order per field: DB row ({@code trust_forwarded_headers} /
 * {@code client_base_host_allowlist} / {@code client_base_url} keys in
 * {@code auth_settings}) → env var ({@code PANTERA_TRUST_FORWARDED_HEADERS}
 * / {@code PANTERA_CLIENT_BASE_HOST_ALLOWLIST} / {@code
 * PANTERA_CLIENT_BASE_URL}) → hardcoded {@link
 * ClientBaseUrlSettings#defaults()}. The env var name for {@code
 * trust_forwarded_headers} is deliberately unchanged from the pre-2.3.0
 * env-only flag it replaces — an existing deployment's
 * {@code PANTERA_TRUST_FORWARDED_HEADERS=true} keeps working unmodified as
 * the fallback tier under a DB row that hasn't been set yet.</p>
 *
 * <p>Because {@link ClientBaseUrl} lives in {@code pantera-core}, which
 * cannot depend on this module, {@link #install(AuthSettingsDao)} also
 * installs this loader into {@link ClientBaseUrlSettingsRegistry} — the
 * {@code pantera-core}-side static holder {@code ClientBaseUrl} actually
 * reads from on every construction. See that registry's Javadoc for the
 * full boundary-crossing rationale.</p>
 *
 * <p>{@code VertxMain} calls {@link #install(AuthSettingsDao)} at boot
 * UNCONDITIONALLY, passing {@code null} when no shared {@code DataSource}
 * is configured (a documented, supported single-instance mode), so the
 * env then default tiers keep resolving on a DB-less boot exactly as the
 * pre-2.3.0 static {@code System.getenv} read did. A {@code null} DAO is
 * not a special case in {@link #load()}: {@code resolveBoolean} /
 * {@code resolveHostAllowlist} treat a {@code null} DAO and a DAO whose
 * {@link AuthSettingsDao#get} returns empty identically -- both fall
 * through to the env tier.</p>
 *
 * @since 2.3.0
 */
public final class ClientBaseUrlSettingsLoader implements Supplier<ClientBaseUrlSettings> {

    /**
     * {@code CacheBroadcast} cache-type name this loader's {@link #invalidate()}
     * is broadcast under, mirroring {@code UpstreamBreakerSettingsLoader#BROADCAST_CHANNEL}.
     */
    public static final String BROADCAST_CHANNEL = "client-base-url-settings";

    /**
     * Deliberately matches the legacy env var name via {@code ENV_PREFIX +
     * KEY_TRUST_FORWARDED.toUpperCase()} — see class Javadoc.
     */
    static final String KEY_TRUST_FORWARDED = "trust_forwarded_headers";

    static final String KEY_HOST_ALLOWLIST = "client_base_host_allowlist";

    /**
     * Canonical client-facing base URL — enforced (not merely a fallback
     * default) for every repository with no explicit {@code url:} once set;
     * see {@link ClientBaseUrl#derive(String, String)}. Env fallback is
     * {@code PANTERA_CLIENT_BASE_URL} via {@link #envName(String)}.
     */
    static final String KEY_CANONICAL_BASE_URL = "client_base_url";

    private static final String ENV_PREFIX = "PANTERA_";

    /**
     * Process-wide singleton installed by {@code VertxMain} after Flyway
     * runs; same lifecycle as {@code UpstreamBreakerSettingsLoader#install}.
     */
    private static volatile ClientBaseUrlSettingsLoader installed;

    /**
     * Install a shared loader backed by the given DAO — {@code dao} may be
     * {@code null} (DB-less boot; see class Javadoc) — and install it into
     * {@link ClientBaseUrlSettingsRegistry} so {@code pantera-core}'s {@link
     * ClientBaseUrl} reads through it. Idempotent.
     * @param dao Auth settings DAO, or {@code null} when no shared
     *  {@code DataSource} is configured
     */
    public static synchronized void install(final AuthSettingsDao dao) {
        ClientBaseUrlSettingsLoader.install(dao, System::getenv);
    }

    /**
     * Test seam: install with an injectable env-var lookup so a resolved
     * env value can be asserted without touching the real process
     * environment. Production callers always go through
     * {@link #install(AuthSettingsDao)}.
     * @param dao Auth settings DAO, or {@code null}
     * @param envLookup Env-var lookup, keyed by the fully-prefixed name
     *  (e.g. {@code System::getenv})
     */
    static synchronized void install(
        final AuthSettingsDao dao, final Function<String, String> envLookup
    ) {
        final ClientBaseUrlSettingsLoader loader =
            new ClientBaseUrlSettingsLoader(dao, envLookup);
        ClientBaseUrlSettingsLoader.installed = loader;
        ClientBaseUrlSettingsRegistry.install(loader);
    }

    /**
     * Clear the installed loader (tests, shutdown) and the registry it fed.
     */
    public static synchronized void uninstall() {
        ClientBaseUrlSettingsLoader.installed = null;
        ClientBaseUrlSettingsRegistry.uninstall();
    }

    /**
     * The installed loader, or {@code null} if none.
     * @return Installed loader
     */
    public static ClientBaseUrlSettingsLoader installed() {
        return ClientBaseUrlSettingsLoader.installed;
    }

    /**
     * Supplier resolving to the installed loader's settings, falling back to
     * {@link ClientBaseUrlSettings#defaults()} when no loader has been
     * installed at all (unit tests that never call {@link #install}). A
     * DB-less production boot still installs a loader (with a {@code null}
     * DAO), so it resolves through the env → default tiers here, not
     * straight to defaults. Safe to call before {@link #install}.
     * @return Active-settings supplier
     */
    public static Supplier<ClientBaseUrlSettings> activeSupplier() {
        return () -> {
            final ClientBaseUrlSettingsLoader current = ClientBaseUrlSettingsLoader.installed;
            return current != null ? current.get() : ClientBaseUrlSettings.defaults();
        };
    }

    private final AuthSettingsDao dao;

    private final Function<String, String> envLookup;

    private final AtomicReference<ClientBaseUrlSettings> cached = new AtomicReference<>();

    /**
     * Public ctor: real env lookup. Delegates to the field-initializing ctor.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     */
    public ClientBaseUrlSettingsLoader(final AuthSettingsDao dao) {
        this(dao, System::getenv);
    }

    /**
     * The single field-initializing constructor; {@link
     * #ClientBaseUrlSettingsLoader(AuthSettingsDao)} delegates here via
     * {@code this(...)}. Package-private: the env-lookup seam exists so
     * tests can assert env-tier resolution deterministically, without
     * touching the real process environment.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     * @param envLookup Env-var lookup, keyed by the fully-prefixed name
     */
    ClientBaseUrlSettingsLoader(final AuthSettingsDao dao, final Function<String, String> envLookup) {
        this.dao = dao;
        this.envLookup = envLookup;
    }

    /**
     * Current cached settings, loading from DB on first call.
     * @return Current settings
     */
    @Override
    public ClientBaseUrlSettings get() {
        final ClientBaseUrlSettings current = this.cached.get();
        if (current != null) {
            return current;
        }
        final ClientBaseUrlSettings loaded = this.load();
        this.cached.compareAndSet(null, loaded);
        return this.cached.get();
    }

    /**
     * Re-read the DB and replace the cached value. Called by the admin
     * endpoint after a successful PUT and by the cross-node broadcast
     * subscriber; every {@link ClientBaseUrl} reads through {@link
     * ClientBaseUrlSettingsRegistry}, so the change applies to the very next
     * construction.
     */
    public void invalidate() {
        this.cached.set(this.load());
    }

    /**
     * Merge DB → env → default per field; an invariant violation from the
     * record constructor degrades to pure defaults, never propagates.
     * @return Loaded settings
     */
    private ClientBaseUrlSettings load() {
        final ClientBaseUrlSettings defaults = ClientBaseUrlSettings.defaults();
        try {
            return new ClientBaseUrlSettings(
                this.resolveBoolean(KEY_TRUST_FORWARDED, defaults.trustForwardedHeaders()),
                this.resolveHostAllowlist(defaults.hostAllowlist()),
                this.resolveCanonicalBaseUrl(defaults.canonicalBaseUrl())
            );
        } catch (final IllegalArgumentException ex) {
            return defaults;
        }
    }

    private boolean resolveBoolean(final String key, final boolean fallback) {
        boolean result = fallback;
        final Optional<String> row = this.dao == null ? Optional.empty() : this.dao.get(key);
        if (row.isPresent()) {
            result = Boolean.parseBoolean(row.get());
        } else {
            final String env = this.envLookup.apply(ClientBaseUrlSettingsLoader.envName(key));
            if (env != null) {
                result = Boolean.parseBoolean(env);
            }
        }
        return result;
    }

    private List<String> resolveHostAllowlist(final List<String> fallback) {
        final Optional<String> row =
            this.dao == null ? Optional.empty() : this.dao.get(KEY_HOST_ALLOWLIST);
        final String raw = row.orElseGet(
            () -> this.envLookup.apply(ClientBaseUrlSettingsLoader.envName(KEY_HOST_ALLOWLIST))
        );
        final List<String> result;
        if (raw == null || raw.isBlank()) {
            result = fallback;
        } else {
            result = Stream.of(raw.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .toList();
        }
        return result;
    }

    /**
     * Resolve the canonical base URL: DB row → env var → hardcoded default
     * (blank, i.e. unset). Validation and trailing-slash normalization
     * happen once, in {@link ClientBaseUrlSettings}'s compact constructor,
     * on every {@link #load()} — not here.
     * @param fallback Hardcoded default (always {@code ""})
     * @return Resolved raw value, before {@link ClientBaseUrlSettings}
     *  validates/normalizes it
     */
    private String resolveCanonicalBaseUrl(final String fallback) {
        final Optional<String> row =
            this.dao == null ? Optional.empty() : this.dao.get(KEY_CANONICAL_BASE_URL);
        final String raw = row.orElseGet(
            () -> this.envLookup.apply(ClientBaseUrlSettingsLoader.envName(KEY_CANONICAL_BASE_URL))
        );
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static String envName(final String key) {
        return ClientBaseUrlSettingsLoader.ENV_PREFIX + key.toUpperCase(Locale.ROOT);
    }
}
