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
package com.auto1.pantera.adapters.docker;

import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.http.log.EcsLogger;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loads the global tag refresh age of docker-proxy repositories: how long a
 * cached image TAG is served before the proxy asks the upstream again
 * whether the tag still names the same digest. A repository overrides it
 * with its own {@code tag_refresh_seconds}
 * ({@code RepoConfig#tagRefresh()}). Mirrors
 * {@code UpstreamBreakerSettingsLoader}.
 *
 * <p>Load order: DB row ({@value #KEY} in {@code auth_settings}) → env var
 * ({@code PANTERA_DOCKER_TAG_REFRESH_SECONDS}) → {@value #DEFAULT_SECONDS}
 * seconds. A tier whose value is not an integer in
 * {@code 0..}{@value #MAX_SECONDS} is skipped. {@code 0} re-checks the tag on
 * every request.</p>
 *
 * <p>{@code VertxMain} calls {@link #install(AuthSettingsDao)} at boot
 * unconditionally, passing {@code null} when no shared {@code DataSource} is
 * configured: a {@code null} DAO resolves exactly like an absent row. The
 * install reads the value at once, on the boot thread, so a docker request
 * never waits for the database; the admin endpoint reloads it with
 * {@link #invalidate()} after a write. Like the upstream breaker settings,
 * a write reloads only the node that served it: another node of a cluster
 * reads the new value at its next start.</p>
 *
 * @since 2.2.15
 */
public final class DockerTagRefreshSettingsLoader implements Supplier<Duration> {

    /**
     * Key of the setting in {@code auth_settings}.
     */
    public static final String KEY = "docker_tag_refresh_seconds";

    /**
     * Age used when no tier holds a valid value: one hour.
     */
    public static final long DEFAULT_SECONDS = 3600L;

    /**
     * Largest accepted age: 365 days.
     */
    public static final long MAX_SECONDS = 31_536_000L;

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.adapters.docker";

    /**
     * Process-wide loader installed by {@code VertxMain} after Flyway runs.
     */
    private static volatile DockerTagRefreshSettingsLoader installed;

    /**
     * Settings table, or {@code null} without a database.
     */
    private final AuthSettingsDao dao;

    /**
     * Env-var lookup, keyed by the full variable name.
     */
    private final Function<String, String> envLookup;

    /**
     * Loaded age.
     */
    private final AtomicReference<Duration> cached = new AtomicReference<>();

    /**
     * Public ctor: real env lookup. Delegates to the field-initializing ctor.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     */
    public DockerTagRefreshSettingsLoader(final AuthSettingsDao dao) {
        this(dao, System::getenv);
    }

    /**
     * The single field-initializing constructor. Package-private: the
     * env-lookup seam lets tests assert the env tier without touching the
     * real process environment.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     * @param envLookup Env-var lookup, keyed by the full variable name
     */
    DockerTagRefreshSettingsLoader(
        final AuthSettingsDao dao, final Function<String, String> envLookup
    ) {
        this.dao = dao;
        this.envLookup = envLookup;
    }

    /**
     * Install the process-wide loader backed by the given DAO and read the
     * value at once. {@code dao} may be {@code null} (DB-less boot).
     * @param dao Auth settings DAO, or {@code null} when no shared
     *  {@code DataSource} is configured
     */
    public static synchronized void install(final AuthSettingsDao dao) {
        DockerTagRefreshSettingsLoader.install(dao, System::getenv);
    }

    /**
     * Test seam: install with an injectable env-var lookup.
     * @param dao Auth settings DAO, or {@code null}
     * @param envLookup Env-var lookup, keyed by the full variable name
     */
    static synchronized void install(
        final AuthSettingsDao dao, final Function<String, String> envLookup
    ) {
        final DockerTagRefreshSettingsLoader loader =
            new DockerTagRefreshSettingsLoader(dao, envLookup);
        loader.get();
        installed = loader;
    }

    /** Clear the installed loader (tests, shutdown). */
    public static synchronized void uninstall() {
        installed = null;
    }

    /**
     * The installed loader.
     * @return Loader, or {@code null} if none is installed
     */
    public static DockerTagRefreshSettingsLoader installed() {
        return installed;
    }

    /**
     * Supplier of the age in force: the installed loader's, or the env
     * var / default when none is installed (tests, a slice built before
     * the boot installs the loader). Safe to call before {@link #install}.
     * @return Supplier to read on every decision
     */
    public static Supplier<Duration> activeSupplier() {
        return () -> {
            final DockerTagRefreshSettingsLoader current = installed;
            final Duration res;
            if (current == null) {
                res = new DockerTagRefreshSettingsLoader(null).get();
            } else {
                res = current.get();
            }
            return res;
        };
    }

    /**
     * Current age, loading it on the first call.
     * @return Tag refresh age
     */
    @Override
    public Duration get() {
        final Duration current = this.cached.get();
        if (current != null) {
            return current;
        }
        final Duration loaded = this.load();
        this.cached.compareAndSet(null, loaded);
        return this.cached.get();
    }

    /**
     * Read the value again and replace the cached one. Called by the admin
     * endpoint after a successful PUT; readers go through
     * {@link #activeSupplier()}, so the next request sees the new age.
     */
    public void invalidate() {
        this.cached.set(this.load());
    }

    /**
     * Read the database row again, for a change made through another node:
     * {@code auth_settings} sends no change notification, so every node
     * calls this periodically. A read that fails keeps the age in use, so a
     * short database outage does not switch it to the env var or the
     * default.
     */
    public void refresh() {
        if (this.dao != null) {
            final OptionalLong row;
            try {
                row = DockerTagRefreshSettingsLoader.seconds(this.dao.get(KEY).orElse(null));
            } catch (final IllegalStateException ex) {
                EcsLogger.debug(LOGGER)
                    .message("Could not re-read " + KEY + " from the database; keeping the age in use")
                    .eventCategory("database")
                    .eventAction("docker_tag_refresh_settings_load")
                    .eventOutcome("failure")
                    .field("log.source", "application")
                    .error(ex)
                    .log();
                return;
            }
            final Duration next = row.isPresent()
                ? Duration.ofSeconds(row.getAsLong())
                : this.load();
            final Duration before = this.cached.getAndSet(next);
            if (before != null && !before.equals(next)) {
                EcsLogger.info(LOGGER)
                    .message(
                        "Docker tag refresh age changed in the database: "
                            + before.toSeconds() + " s -> " + next.toSeconds() + " s"
                    )
                    .eventCategory("configuration")
                    .eventAction("docker_tag_refresh_settings_load")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            }
        }
    }

    /**
     * DB row → env var → default; an invalid tier is skipped.
     * @return Age
     */
    private Duration load() {
        OptionalLong seconds = this.row();
        if (seconds.isEmpty()) {
            seconds = DockerTagRefreshSettingsLoader.seconds(
                this.envLookup.apply("PANTERA_" + KEY.toUpperCase(Locale.ROOT))
            );
        }
        return Duration.ofSeconds(seconds.orElse(DEFAULT_SECONDS));
    }

    /**
     * The database tier. A database that cannot be read counts as an absent
     * row, so the age falls back to the env var / default instead of failing
     * a docker request.
     * @return Valid seconds from the row, or empty
     */
    private OptionalLong row() {
        OptionalLong res = OptionalLong.empty();
        if (this.dao != null) {
            try {
                res = DockerTagRefreshSettingsLoader.seconds(this.dao.get(KEY).orElse(null));
            } catch (final IllegalStateException ex) {
                EcsLogger.warn(LOGGER)
                    .message("Could not read " + KEY + " from the database; using "
                        + "PANTERA_DOCKER_TAG_REFRESH_SECONDS or the default")
                    .eventCategory("database")
                    .eventAction("docker_tag_refresh_settings_load")
                    .eventOutcome("failure")
                    .field("log.source", "application")
                    .error(ex)
                    .log();
            }
        }
        return res;
    }

    /**
     * Parse a tier's value.
     * @param raw Value, or {@code null}
     * @return Seconds when the value is an integer in {@code 0..MAX_SECONDS}
     */
    private static OptionalLong seconds(final String raw) {
        OptionalLong res = OptionalLong.empty();
        if (raw != null) {
            try {
                final long value = Long.parseLong(raw.trim());
                if (value >= 0 && value <= MAX_SECONDS) {
                    res = OptionalLong.of(value);
                }
            } catch (final NumberFormatException ignored) {
                // skipped: the next tier applies
            }
        }
        return res;
    }
}
