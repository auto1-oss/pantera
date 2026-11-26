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
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loads {@link SignInReportConfig}: the {@code auth_settings} rows
 * {@code sign_in_report_enabled} and {@code sign_in_report_retention_days},
 * then {@code PANTERA_SIGN_IN_REPORT_ENABLED} and
 * {@code PANTERA_SIGN_IN_REPORT_RETENTION_DAYS}, then the defaults. A
 * value that cannot be used is skipped.
 *
 * @since 2.2.15
 */
public final class SignInReportSettingsLoader implements Supplier<SignInReportConfig> {

    /**
     * Key: whether sign-in failures are noted and stored.
     */
    public static final String KEY_ENABLED = "sign_in_report_enabled";

    /**
     * Key: days a line is kept after its last event.
     */
    public static final String KEY_RETENTION = "sign_in_report_retention_days";

    /**
     * Process-wide loader.
     */
    private static volatile SignInReportSettingsLoader installed;

    /**
     * Where values come from.
     */
    private final SettingSource source;

    /**
     * Loaded policy.
     */
    private final AtomicReference<SignInReportConfig> cached = new AtomicReference<>();

    /**
     * Ctor.
     *
     * @param dao Settings table, null without a database
     */
    public SignInReportSettingsLoader(final AuthSettingsDao dao) {
        this(dao, System::getenv);
    }

    /**
     * Ctor.
     *
     * @param dao Settings table, null without a database
     * @param env Environment lookup
     */
    SignInReportSettingsLoader(final AuthSettingsDao dao, final Function<String, String> env) {
        this.source = new SettingSource(dao, env);
    }

    /**
     * Install the process-wide loader.
     *
     * @param dao Settings table, null without a database
     */
    public static synchronized void install(final AuthSettingsDao dao) {
        SignInReportSettingsLoader.install(dao, System::getenv);
    }

    /**
     * Install the process-wide loader.
     *
     * @param dao Settings table, null without a database
     * @param env Environment lookup
     */
    static synchronized void install(final AuthSettingsDao dao, final Function<String, String> env) {
        SignInReportSettingsLoader.installed = new SignInReportSettingsLoader(dao, env);
    }

    /**
     * Remove the process-wide loader (tests).
     */
    public static synchronized void uninstall() {
        SignInReportSettingsLoader.installed = null;
    }

    /**
     * The process-wide loader.
     *
     * @return Loader, or null when none is installed
     */
    public static SignInReportSettingsLoader installed() {
        return SignInReportSettingsLoader.installed;
    }

    /**
     * Supplier of the policy in force: the installed loader's, or
     * environment and defaults when none is installed.
     *
     * @return Supplier to read on every decision
     */
    public static Supplier<SignInReportConfig> activeSupplier() {
        return () -> {
            final SignInReportSettingsLoader current = SignInReportSettingsLoader.installed;
            final SignInReportConfig res;
            if (current == null) {
                res = new SignInReportSettingsLoader(null).get();
            } else {
                res = current.get();
            }
            return res;
        };
    }

    @Override
    public SignInReportConfig get() {
        SignInReportConfig current = this.cached.get();
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

    private SignInReportConfig load() {
        final SignInReportConfig def = SignInReportConfig.defaults();
        final boolean enabled = this.source.row(SignInReportSettingsLoader.KEY_ENABLED)
            .flatMap(SignInReportSettingsLoader::flag)
            .or(
                () -> this.source.env(SignInReportSettingsLoader.KEY_ENABLED)
                    .flatMap(SignInReportSettingsLoader::flag)
            )
            .orElse(def.enabled());
        final int days = this.source.row(SignInReportSettingsLoader.KEY_RETENTION)
            .flatMap(SignInReportSettingsLoader::days)
            .or(
                () -> this.source.env(SignInReportSettingsLoader.KEY_RETENTION)
                    .flatMap(SignInReportSettingsLoader::days)
            )
            .orElse(def.retentionDays());
        return new SignInReportConfig(enabled, days);
    }

    private static Optional<Boolean> flag(final String value) {
        final String text = value.trim().toLowerCase(Locale.ROOT);
        final Optional<Boolean> res;
        if ("true".equals(text)) {
            res = Optional.of(Boolean.TRUE);
        } else if ("false".equals(text)) {
            res = Optional.of(Boolean.FALSE);
        } else {
            res = Optional.empty();
        }
        return res;
    }

    private static Optional<Integer> days(final String value) {
        Optional<Integer> res;
        try {
            final int days = Integer.parseInt(value.trim());
            new SignInReportConfig(true, days);
            res = Optional.of(days);
        } catch (final IllegalArgumentException bad) {
            res = Optional.empty();
        }
        return res;
    }
}
