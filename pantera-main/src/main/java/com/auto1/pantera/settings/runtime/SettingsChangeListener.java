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
package com.auto1.pantera.settings.runtime;

/**
 * Callback for settings hot-reload notifications. Invoked by
 * {@link PgListenNotify} for every {@code settings_changed} NOTIFY
 * payload received from PostgreSQL.
 *
 * @since 2.2.0
 */
@FunctionalInterface
public interface SettingsChangeListener {

    /**
     * Called when a settings key has changed in the database.
     * Implementations must be exception-safe; throwing here is logged
     * and swallowed by the worker so the LISTEN loop survives.
     *
     * @param key the settings key that changed
     */
    void onChanged(String key);
}
