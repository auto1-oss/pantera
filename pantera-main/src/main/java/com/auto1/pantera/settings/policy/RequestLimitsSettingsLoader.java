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
import com.auto1.pantera.settings.repo.FsStorageRootPolicy;
import java.util.function.LongSupplier;

/**
 * Resolves {@link RequestLimitsConfig} from the {@code auth_settings} table, then the
 * {@code PANTERA_*} environment variables, then documented defaults --
 * the same shape as {@code UpstreamBreakerSettingsLoader}. Keys:
 * {@value #KEY_MAX_BODY} (env {@code PANTERA_MAX_REQUEST_BODY_BYTES}) and
 * {@value #KEY_FS_ROOTS} (env {@code PANTERA_FS_STORAGE_ROOTS}, with the
 * {@code pantera.fs.storage.roots} system property honoured between the
 * DB and environment tiers as an operator/test override).
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
public final class RequestLimitsSettingsLoader implements Supplier<RequestLimitsConfig> {

    /**
     * Key: hard cap on a single request body, bytes.
     */
    public static final String KEY_MAX_BODY = "max_request_body_bytes";

    /**
     * Key: approved inline fs storage roots, path-separator delimited.
     */
    public static final String KEY_FS_ROOTS = "fs_storage_roots";


    /**
     * Installed singleton, or null.
     */
    private static volatile RequestLimitsSettingsLoader installed;

    /**
     * DB + environment tiers.
     */
    private final SettingSource source;

    /**
     * Cached config.
     */
    private final AtomicReference<RequestLimitsConfig> cached = new AtomicReference<>();

    /**
     * Public ctor: real env lookup.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     */
    public RequestLimitsSettingsLoader(final AuthSettingsDao dao) {
        this(dao, System::getenv);
    }

    /**
     * The single field-initializing ctor; the env-lookup seam lets tests
     * assert env-tier resolution without touching the process environment.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     * @param envLookup Env-var lookup, keyed by the fully-prefixed name
     */
    RequestLimitsSettingsLoader(final AuthSettingsDao dao, final Function<String, String> envLookup) {
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
        installed = new RequestLimitsSettingsLoader(dao, envLookup);
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
    public static RequestLimitsSettingsLoader installed() {
        return installed;
    }

    /**
     * Supplier resolving the installed loader's config; before {@link
     * #install} (tests, early boot) it resolves the environment tier and
     * defaults afresh on every call, exactly as a DB-less loader would.
     * @return Live supplier
     */
    public static Supplier<RequestLimitsConfig> activeSupplier() {
        return () -> {
            final RequestLimitsSettingsLoader current = installed;
            return current != null ? current.get() : new RequestLimitsSettingsLoader(null).get();
        };
    }

    /**
     * Live request-body cap for the HTTP server (evaluated per request).
     * @return Supplier
     */
    public static LongSupplier maxRequestBodyBytes() {
        final Supplier<RequestLimitsConfig> active = activeSupplier();
        return () -> active.get().maxRequestBodyBytes();
    }

    /**
     * Live fs storage root policy (evaluated per repository write).
     * @return Supplier
     */
    public static Supplier<FsStorageRootPolicy> fsRootPolicy() {
        final Supplier<RequestLimitsConfig> active = activeSupplier();
        return () -> active.get().fsRootPolicy();
    }

    @Override
    public RequestLimitsConfig get() {
        RequestLimitsConfig current = this.cached.get();
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

    private RequestLimitsConfig load() {
        final SettingFallback fallback = new SettingFallback("request_limits");
        final long body = fallback.validOrDefault(
            KEY_MAX_BODY,
            this.source.resolveLong(KEY_MAX_BODY, RequestLimitsConfig.DEFAULT_MAX_REQUEST_BODY_BYTES),
            val -> new RequestLimitsConfig(val, FsStorageRootPolicy.DEFAULT),
            RequestLimitsConfig.DEFAULT_MAX_REQUEST_BODY_BYTES
        );
        final String roots = fallback.validOrDefault(
            KEY_FS_ROOTS,
            this.rootsCandidate(),
            val -> new RequestLimitsConfig(RequestLimitsConfig.DEFAULT_MAX_REQUEST_BODY_BYTES, val),
            FsStorageRootPolicy.DEFAULT
        );
        return new RequestLimitsConfig(body, roots);
    }

    /**
     * Roots candidate: DB row, then the system property, then the env var,
     * then the built-in default.
     * @return Unvalidated roots list
     */
    private String rootsCandidate() {
        return this.source.row(KEY_FS_ROOTS)
            .or(() -> java.util.Optional.ofNullable(System.getProperty(FsStorageRootPolicy.PROPERTY))
                .filter(val -> !val.isBlank()))
            .or(() -> this.source.env(KEY_FS_ROOTS))
            .orElse(FsStorageRootPolicy.DEFAULT);
    }
}
