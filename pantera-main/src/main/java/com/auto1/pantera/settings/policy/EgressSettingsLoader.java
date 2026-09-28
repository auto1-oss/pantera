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
import com.auto1.pantera.http.client.egress.EgressSettingsRegistry;

/**
 * Resolves {@link EgressConfig} from the {@code auth_settings} table, then the
 * {@code PANTERA_*} environment variables, then documented defaults --
 * the same shape as {@code UpstreamBreakerSettingsLoader}. Keys:
 * {@value #KEY_BLOCK_PRIVATE}, {@value #KEY_ALLOW_HOSTS} and
 * {@value #KEY_CREDENTIAL_HOSTS} (env {@code PANTERA_EGRESS_BLOCK_PRIVATE},
 * {@code PANTERA_EGRESS_ALLOW_HOSTS}, {@code PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS}).
 * {@link #install} also feeds http-client's {@link EgressSettingsRegistry},
 * which is how the Jetty address resolver and the bearer-realm trust check
 * (modules that cannot see the DAO) read these settings live.
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
public final class EgressSettingsLoader implements Supplier<EgressConfig> {

    /**
     * Key: refuse private/loopback/link-local destinations.
     */
    public static final String KEY_BLOCK_PRIVATE = "egress_block_private";

    /**
     * Key: hosts exempt from that refusal, comma-separated.
     */
    public static final String KEY_ALLOW_HOSTS = "egress_allow_hosts";

    /**
     * Key: hosts trusted to receive upstream credentials, comma-separated.
     */
    public static final String KEY_CREDENTIAL_HOSTS = "upstream_credential_allow_hosts";


    /**
     * Installed singleton, or null.
     */
    private static volatile EgressSettingsLoader installed;

    /**
     * DB + environment tiers.
     */
    private final SettingSource source;

    /**
     * Cached config.
     */
    private final AtomicReference<EgressConfig> cached = new AtomicReference<>();

    /**
     * Public ctor: real env lookup.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     */
    public EgressSettingsLoader(final AuthSettingsDao dao) {
        this(dao, System::getenv);
    }

    /**
     * The single field-initializing ctor; the env-lookup seam lets tests
     * assert env-tier resolution without touching the process environment.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     * @param envLookup Env-var lookup, keyed by the fully-prefixed name
     */
    EgressSettingsLoader(final AuthSettingsDao dao, final Function<String, String> envLookup) {
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
        installed = new EgressSettingsLoader(dao, envLookup);
        final Supplier<EgressConfig> active = activeSupplier();
        EgressSettingsRegistry.install(
            () -> active.get().policy(), () -> active.get().credentialAllowHosts()
        );
    }

    /**
     * Drop the installed loader (tests, shutdown).
     */
    public static synchronized void uninstall() {
        installed = null;
        EgressSettingsRegistry.uninstall();
    }

    /**
     * The installed loader.
     * @return Loader, or null
     */
    public static EgressSettingsLoader installed() {
        return installed;
    }

    /**
     * Supplier resolving the installed loader's config; before {@link
     * #install} (tests, early boot) it resolves the environment tier and
     * defaults afresh on every call, exactly as a DB-less loader would.
     * @return Live supplier
     */
    public static Supplier<EgressConfig> activeSupplier() {
        return () -> {
            final EgressSettingsLoader current = installed;
            return current != null ? current.get() : new EgressSettingsLoader(null).get();
        };
    }

    @Override
    public EgressConfig get() {
        EgressConfig current = this.cached.get();
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

    private EgressConfig load() {
        final SettingFallback fallback = new SettingFallback("egress");
        final String allow = fallback.validOrDefault(
            KEY_ALLOW_HOSTS,
            this.source.resolve(KEY_ALLOW_HOSTS).orElse(""),
            val -> new EgressConfig(false, val, ""),
            ""
        );
        final String credential = fallback.validOrDefault(
            KEY_CREDENTIAL_HOSTS,
            this.source.resolve(KEY_CREDENTIAL_HOSTS).orElse(""),
            val -> new EgressConfig(false, "", val),
            ""
        );
        return new EgressConfig(this.source.resolveBoolean(KEY_BLOCK_PRIVATE, false), allow, credential);
    }
}
