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
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Loads the policy for failed logins ({@link FailedLoginsConfig}): for each
 * value the {@code auth_settings} row, then the environment variable
 * {@code PANTERA_<KEY>}, then the default. A value that cannot be used
 * falls back to its default alone; the others are kept.
 *
 * <p>Consumers read through {@link #activeSupplier()} on every decision,
 * so a change made in System Settings applies without a restart, after
 * {@link #invalidate()}.</p>
 *
 * @since 2.2.15
 */
public final class FailedLoginsSettingsLoader implements Supplier<FailedLoginsConfig> {

    /**
     * Key: failure number from which logins are delayed.
     */
    public static final String KEY_DELAY_FROM = "failed_logins_delay_from_failure";

    /**
     * Key: delay unit in seconds.
     */
    public static final String KEY_FIRST_DELAY = "failed_logins_first_delay_seconds";

    /**
     * Key: longest delay in seconds.
     */
    public static final String KEY_MAX_DELAY = "failed_logins_max_delay_seconds";

    /**
     * Key: failures after which a user is locked.
     */
    public static final String KEY_LOCK_AFTER = "failed_logins_lock_after";

    /**
     * Key: minutes after which a lock ends by itself.
     */
    public static final String KEY_UNLOCK_AFTER = "failed_logins_unlock_after_minutes";

    /**
     * Key: minutes after which failures are forgotten.
     */
    public static final String KEY_FORGET_AFTER = "failed_logins_forget_after_minutes";

    /**
     * Key: whether a rejected API key counts as a failed login.
     */
    public static final String KEY_COUNT_KEYS = "failed_logins_count_rejected_keys";

    /**
     * Key: whether a lock blocks every credential or password sign-in only.
     */
    public static final String KEY_LOCK_ALL = "failed_logins_lock_all_credentials";

    /**
     * Key: unconfirmed second factors after which password sign-in pauses.
     */
    public static final String KEY_SECOND_FACTOR_LIMIT = "failed_logins_second_factor_limit";

    /**
     * Key: minutes the pause after unconfirmed second factors lasts.
     */
    public static final String KEY_SECOND_FACTOR_PAUSE =
        "failed_logins_second_factor_pause_minutes";

    /**
     * Process-wide loader.
     */
    private static volatile FailedLoginsSettingsLoader installed;

    /**
     * Where values come from.
     */
    private final SettingSource source;

    /**
     * Loaded policy.
     */
    private final AtomicReference<FailedLoginsConfig> cached = new AtomicReference<>();

    /**
     * Ctor.
     *
     * @param dao Settings table, null without a database
     */
    public FailedLoginsSettingsLoader(final AuthSettingsDao dao) {
        this(dao, System::getenv);
    }

    /**
     * Ctor.
     *
     * @param dao Settings table, null without a database
     * @param env Environment lookup
     */
    FailedLoginsSettingsLoader(final AuthSettingsDao dao, final Function<String, String> env) {
        this.source = new SettingSource(dao, env);
    }

    /**
     * Install the process-wide loader.
     *
     * @param dao Settings table, null without a database
     */
    public static synchronized void install(final AuthSettingsDao dao) {
        FailedLoginsSettingsLoader.install(dao, System::getenv);
    }

    /**
     * Install the process-wide loader.
     *
     * @param dao Settings table, null without a database
     * @param env Environment lookup
     */
    static synchronized void install(final AuthSettingsDao dao, final Function<String, String> env) {
        FailedLoginsSettingsLoader.installed = new FailedLoginsSettingsLoader(dao, env);
    }

    /**
     * Remove the process-wide loader (tests).
     */
    public static synchronized void uninstall() {
        FailedLoginsSettingsLoader.installed = null;
    }

    /**
     * The process-wide loader.
     *
     * @return Loader, or null when none is installed
     */
    public static FailedLoginsSettingsLoader installed() {
        return FailedLoginsSettingsLoader.installed;
    }

    /**
     * Supplier of the policy in force: the installed loader's, or
     * environment and defaults when none is installed.
     *
     * @return Supplier to read on every decision
     */
    public static Supplier<FailedLoginsConfig> activeSupplier() {
        return () -> {
            final FailedLoginsSettingsLoader current = FailedLoginsSettingsLoader.installed;
            final FailedLoginsConfig res;
            if (current == null) {
                res = new FailedLoginsSettingsLoader(null).get();
            } else {
                res = current.get();
            }
            return res;
        };
    }

    @Override
    public FailedLoginsConfig get() {
        FailedLoginsConfig current = this.cached.get();
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

    private FailedLoginsConfig load() {
        final FailedLoginsConfig def = FailedLoginsConfig.defaults();
        final int from = this.value(
            FailedLoginsSettingsLoader.KEY_DELAY_FROM, def.delayFromFailure(),
            val -> new FailedLoginsConfig(
                val, def.firstDelaySeconds(), def.maxDelaySeconds(), def.lockAfterFailures(),
                def.unlockAfterMinutes(), def.forgetAfterMinutes(), false
            )
        );
        final int first = this.value(
            FailedLoginsSettingsLoader.KEY_FIRST_DELAY, def.firstDelaySeconds(),
            val -> new FailedLoginsConfig(
                def.delayFromFailure(), val, FailedLoginsConfig.MAX_MAX_DELAY,
                def.lockAfterFailures(), def.unlockAfterMinutes(), def.forgetAfterMinutes(), false
            )
        );
        int max = this.value(
            FailedLoginsSettingsLoader.KEY_MAX_DELAY, def.maxDelaySeconds(),
            val -> new FailedLoginsConfig(
                def.delayFromFailure(), 1, val, def.lockAfterFailures(),
                def.unlockAfterMinutes(), def.forgetAfterMinutes(), false
            )
        );
        if (max < first) {
            // Each is valid alone, the pair is not: the first delay is what
            // an admin set last on purpose, the longest takes its default.
            max = Math.max(first, def.maxDelaySeconds());
        }
        final int lock = this.value(
            FailedLoginsSettingsLoader.KEY_LOCK_AFTER, def.lockAfterFailures(),
            val -> new FailedLoginsConfig(
                def.delayFromFailure(), def.firstDelaySeconds(), def.maxDelaySeconds(), val,
                def.unlockAfterMinutes(), def.forgetAfterMinutes(), false
            )
        );
        final int unlock = this.value(
            FailedLoginsSettingsLoader.KEY_UNLOCK_AFTER, def.unlockAfterMinutes(),
            val -> new FailedLoginsConfig(
                def.delayFromFailure(), def.firstDelaySeconds(), def.maxDelaySeconds(),
                def.lockAfterFailures(), val, def.forgetAfterMinutes(), false
            )
        );
        final int forget = this.value(
            FailedLoginsSettingsLoader.KEY_FORGET_AFTER, def.forgetAfterMinutes(),
            val -> new FailedLoginsConfig(
                def.delayFromFailure(), def.firstDelaySeconds(), def.maxDelaySeconds(),
                def.lockAfterFailures(), def.unlockAfterMinutes(), val, false
            )
        );
        final int limit = this.value(
            FailedLoginsSettingsLoader.KEY_SECOND_FACTOR_LIMIT, def.secondFactorLimit(),
            val -> def.withSecondFactor(val, def.secondFactorPauseMinutes())
        );
        final int pause = this.value(
            FailedLoginsSettingsLoader.KEY_SECOND_FACTOR_PAUSE, def.secondFactorPauseMinutes(),
            val -> def.withSecondFactor(def.secondFactorLimit(), val)
        );
        return new FailedLoginsConfig(
            from, first, max, lock, unlock, forget,
            this.source.resolveBoolean(FailedLoginsSettingsLoader.KEY_COUNT_KEYS, false),
            this.source.resolveBoolean(FailedLoginsSettingsLoader.KEY_LOCK_ALL, true),
            limit, pause
        );
    }

    /**
     * One value: the configured one when it can be used, else its default.
     *
     * @param key Settings key
     * @param def Default
     * @param check Throws when the value cannot be used
     * @return Value
     */
    private int value(final String key, final int def, final IntConsumer check) {
        int configured;
        try {
            configured = this.source.resolveInt(key, def);
        } catch (final ArithmeticException big) {
            configured = Integer.MIN_VALUE;
        }
        return new SettingFallback("failed_logins")
            .<Integer>validOrDefault(key, configured, check::accept, def);
    }
}
