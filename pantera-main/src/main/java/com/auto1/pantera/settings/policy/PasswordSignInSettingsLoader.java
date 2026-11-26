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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loads {@link PasswordSignInConfig}: for every provider type the
 * {@code auth_settings} row {@code password_sign_in_<type>}, then the
 * environment variable {@code PANTERA_PASSWORD_SIGN_IN_<TYPE>}, then
 * {@code all}. A value that is no mode is skipped.
 *
 * <p>The policy is read through the supplier on every login, so a change
 * applies without a restart: the node that saves it reloads at once, the
 * others when the change is broadcast.</p>
 *
 * @since 2.2.15
 */
public final class PasswordSignInSettingsLoader implements Supplier<PasswordSignInConfig> {

    /**
     * Prefix of the settings keys; the provider type follows.
     */
    public static final String KEY_PREFIX = "password_sign_in_";

    /**
     * Process-wide loader.
     */
    private static volatile PasswordSignInSettingsLoader installed;

    /**
     * Where values come from.
     */
    private final SettingSource source;

    /**
     * Loaded policy.
     */
    private final AtomicReference<PasswordSignInConfig> cached = new AtomicReference<>();

    /**
     * Ctor.
     *
     * @param dao Settings table, null without a database
     */
    public PasswordSignInSettingsLoader(final AuthSettingsDao dao) {
        this(dao, System::getenv);
    }

    /**
     * Ctor.
     *
     * @param dao Settings table, null without a database
     * @param env Environment lookup
     */
    PasswordSignInSettingsLoader(final AuthSettingsDao dao, final Function<String, String> env) {
        this.source = new SettingSource(dao, env);
    }

    /**
     * Install the process-wide loader.
     *
     * @param dao Settings table, null without a database
     */
    public static synchronized void install(final AuthSettingsDao dao) {
        PasswordSignInSettingsLoader.install(dao, System::getenv);
    }

    /**
     * Install the process-wide loader.
     *
     * @param dao Settings table, null without a database
     * @param env Environment lookup
     */
    static synchronized void install(final AuthSettingsDao dao, final Function<String, String> env) {
        PasswordSignInSettingsLoader.installed = new PasswordSignInSettingsLoader(dao, env);
    }

    /**
     * Remove the process-wide loader (tests).
     */
    public static synchronized void uninstall() {
        PasswordSignInSettingsLoader.installed = null;
    }

    /**
     * The process-wide loader.
     *
     * @return Loader, or null when none is installed
     */
    public static PasswordSignInSettingsLoader installed() {
        return PasswordSignInSettingsLoader.installed;
    }

    /**
     * Supplier of the policy in force: the installed loader's, or
     * environment and defaults when none is installed.
     *
     * @return Supplier to read on every login
     */
    public static Supplier<PasswordSignInConfig> activeSupplier() {
        return () -> {
            final PasswordSignInSettingsLoader current = PasswordSignInSettingsLoader.installed;
            final PasswordSignInConfig res;
            if (current == null) {
                res = new PasswordSignInSettingsLoader(null).get();
            } else {
                res = current.get();
            }
            return res;
        };
    }

    @Override
    public PasswordSignInConfig get() {
        PasswordSignInConfig current = this.cached.get();
        if (current == null) {
            current = this.load();
            this.cached.compareAndSet(null, current);
        }
        return current;
    }

    /**
     * Load the policy again, after a change of the settings.
     */
    public void invalidate() {
        this.cached.set(this.load());
    }

    private PasswordSignInConfig load() {
        final Map<String, String> modes = new HashMap<>();
        for (final String type : PasswordSignInConfig.TYPES) {
            final String key = PasswordSignInSettingsLoader.KEY_PREFIX + type;
            modes.put(
                type,
                this.source.row(key).flatMap(PasswordSignInSettingsLoader::mode)
                    .or(() -> this.source.env(key).flatMap(PasswordSignInSettingsLoader::mode))
                    .orElse(PasswordSignInConfig.ALL)
            );
        }
        return new PasswordSignInConfig(modes);
    }

    private static Optional<String> mode(final String value) {
        Optional<String> mode;
        try {
            mode = Optional.of(PasswordSignInConfig.checked(value));
        } catch (final IllegalArgumentException bad) {
            mode = Optional.empty();
        }
        return mode;
    }
}
