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
package com.auto1.pantera.settings.policy;

import com.auto1.pantera.db.dao.AuthSettingsDao;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves {@link LoginThrottleConfig} from the {@code auth_settings} table, then the
 * {@code PANTERA_*} environment variables, then documented defaults --
 * the same shape as {@code UpstreamBreakerSettingsLoader}. Keys:
 * {@value #KEY_MAX_FAILURES} and {@value #KEY_WINDOW_SECONDS} (env
 * {@code PANTERA_LOGIN_THROTTLE_MAX_FAILURES}, {@code PANTERA_LOGIN_THROTTLE_WINDOW_SECONDS}).
 *
 * <p>The resolved config is cached in an {@link AtomicReference}; {@link
 * #invalidate()} reloads it after an admin write, so consumers reading
 * through {@link #activeSupplier()} on every decision observe the change
 * without a restart and without touching the database on the hot path.
 * Installed unconditionally at boot ({@code null} DAO on a DB-less boot)
 * so the environment tier is always consulted.</p>
 *
 * @since 2.2.9
 */
public final class LoginThrottleSettingsLoader implements Supplier<LoginThrottleConfig> {

    /**
     * Key: failures per (user, client IP) before lockout.
     */
    public static final String KEY_MAX_FAILURES = "login_throttle_max_failures";

    /**
     * Key: lockout window in seconds.
     */
    public static final String KEY_WINDOW_SECONDS = "login_throttle_window_seconds";


    /**
     * Installed singleton, or null.
     */
    private static volatile LoginThrottleSettingsLoader installed;

    /**
     * DB + environment tiers.
     */
    private final SettingSource source;

    /**
     * Cached config.
     */
    private final AtomicReference<LoginThrottleConfig> cached = new AtomicReference<>();

    /**
     * Public ctor: real env lookup.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     */
    public LoginThrottleSettingsLoader(final AuthSettingsDao dao) {
        this(dao, System::getenv);
    }

    /**
     * The single field-initializing ctor; the env-lookup seam lets tests
     * assert env-tier resolution without touching the process environment.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     * @param envLookup Env-var lookup, keyed by the fully-prefixed name
     */
    LoginThrottleSettingsLoader(final AuthSettingsDao dao, final Function<String, String> envLookup) {
        this.source = new SettingSource(dao, envLookup);
    }

    /**
     * Install the process-wide loader (boot).
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     */
    public static synchronized void install(final AuthSettingsDao dao) {
        install(dao, System::getenv);
    }

    /**
     * Install with an explicit env lookup (tests).
     * @param dao Auth settings DAO, or {@code null}
     * @param envLookup Env-var lookup
     */
    static synchronized void install(final AuthSettingsDao dao, final Function<String, String> envLookup) {
        installed = new LoginThrottleSettingsLoader(dao, envLookup);
    }

    /**
     * Drop the installed loader (tests, shutdown).
     */
    public static synchronized void uninstall() {
        installed = null;
    }

    /**
     * The installed loader.
     * @return Loader, or null
     */
    public static LoginThrottleSettingsLoader installed() {
        return installed;
    }

    /**
     * Supplier resolving the installed loader's config; before {@link
     * #install} (tests, early boot) it resolves the environment tier and
     * defaults afresh on every call, exactly as a DB-less loader would.
     * @return Live supplier
     */
    public static Supplier<LoginThrottleConfig> activeSupplier() {
        return () -> {
            final LoginThrottleSettingsLoader current = installed;
            return current != null ? current.get() : new LoginThrottleSettingsLoader(null).get();
        };
    }

    @Override
    public LoginThrottleConfig get() {
        LoginThrottleConfig current = this.cached.get();
        if (current == null) {
            current = this.load();
            this.cached.compareAndSet(null, current);
        }
        return current;
    }

    /**
     * Reload after an admin write.
     */
    public void invalidate() {
        this.cached.set(this.load());
    }

    private LoginThrottleConfig load() {
        final SettingFallback fallback = new SettingFallback("login_throttle");
        final int failures = fallback.validOrDefault(
            KEY_MAX_FAILURES,
            this.source.resolveInt(KEY_MAX_FAILURES, LoginThrottleConfig.DEFAULT_MAX_FAILURES),
            val -> new LoginThrottleConfig(val, LoginThrottleConfig.DEFAULT_WINDOW_SECONDS),
            LoginThrottleConfig.DEFAULT_MAX_FAILURES
        );
        final int window = fallback.validOrDefault(
            KEY_WINDOW_SECONDS,
            this.source.resolveInt(KEY_WINDOW_SECONDS, LoginThrottleConfig.DEFAULT_WINDOW_SECONDS),
            val -> new LoginThrottleConfig(LoginThrottleConfig.DEFAULT_MAX_FAILURES, val),
            LoginThrottleConfig.DEFAULT_WINDOW_SECONDS
        );
        return new LoginThrottleConfig(failures, window);
    }
}
