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
import java.util.Optional;
import java.util.function.Function;

/**
 * The two configurable tiers shared by the policy loaders: the {@code
 * auth_settings} row when present and non-blank, else the environment
 * variable. Callers apply their own hardcoded default when both are absent.
 * Defaults are deliberately not seeded into the table (V138): a seeded row
 * would shadow the environment tier.
 *
 * @since 2.2.9
 */
final class SettingSource {

    /**
     * Prefix of every environment variable.
     */
    private static final String ENV_PREFIX = "PANTERA_";

    /**
     * DAO, or null on a DB-less boot.
     */
    private final AuthSettingsDao dao;

    /**
     * Environment lookup by full variable name.
     */
    private final Function<String, String> envLookup;

    /**
     * Ctor.
     * @param dao Auth settings DAO, or {@code null} for a DB-less boot
     * @param envLookup Env-var lookup, keyed by the fully-prefixed name
     */
    SettingSource(final AuthSettingsDao dao, final Function<String, String> envLookup) {
        this.dao = dao;
        this.envLookup = envLookup;
    }

    /**
     * Resolve a key through the DB and environment tiers.
     * @param key Settings key (its env var is {@code PANTERA_<KEY>} upper-cased)
     * @return Trimmed value, or empty when neither tier holds one
     */
    Optional<String> resolve(final String key) {
        return this.row(key).or(() -> this.env(key));
    }

    /**
     * The database tier alone.
     * @param key Settings key
     * @return Trimmed non-blank row value, or empty
     */
    Optional<String> row(final String key) {
        if (this.dao == null) {
            return Optional.empty();
        }
        return this.dao.get(key).map(String::trim).filter(val -> !val.isEmpty());
    }

    /**
     * The environment tier alone.
     * @param key Settings key
     * @return Trimmed non-blank variable value, or empty
     */
    Optional<String> env(final String key) {
        return Optional.ofNullable(this.envLookup.apply(ENV_PREFIX + key.toUpperCase(java.util.Locale.ROOT)))
            .map(String::trim).filter(val -> !val.isEmpty());
    }

    /**
     * Resolve a long, skipping unparsable tiers.
     * @param key Settings key
     * @param fallback Hardcoded default
     * @return Value
     */
    long resolveLong(final String key, final long fallback) {
        return this.resolve(key).map(val -> {
            try {
                return Long.parseLong(val);
            } catch (final NumberFormatException bad) {
                return null;
            }
        }).orElse(fallback);
    }

    /**
     * Resolve an int, skipping unparsable tiers.
     * @param key Settings key
     * @param fallback Hardcoded default
     * @return Value
     */
    int resolveInt(final String key, final int fallback) {
        return Math.toIntExact(this.resolveLong(key, fallback));
    }

    /**
     * Resolve a boolean ({@code true}/{@code false}, case-insensitive).
     * @param key Settings key
     * @param fallback Hardcoded default
     * @return Value
     */
    boolean resolveBoolean(final String key, final boolean fallback) {
        return this.resolve(key).map(Boolean::parseBoolean).orElse(fallback);
    }
}
