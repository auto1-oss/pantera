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
package com.auto1.pantera.settings.users;

import java.util.Optional;
import javax.json.JsonArray;
import javax.json.JsonObject;

/**
 * Composite CrudUsers that delegates to a primary (DB) and secondary (YAML)
 * implementation. Writes go to both; reads come from primary.
 * This ensures DB-backed users also get YAML files for the YAML-based
 * policy to resolve roles and permissions.
 *
 * @since 1.21
 */
public final class DualCrudUsers implements CrudUsers {

    /**
     * Primary user storage (DB).
     */
    private final CrudUsers primary;

    /**
     * Secondary user storage (YAML policy files).
     */
    private final CrudUsers secondary;

    /**
     * Ctor.
     * @param primary Primary (DB) user storage
     * @param secondary Secondary (YAML) user storage
     */
    public DualCrudUsers(final CrudUsers primary, final CrudUsers secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public JsonArray list() {
        return this.primary.list();
    }

    @Override
    public Optional<JsonObject> get(final String uname) {
        return this.primary.get(uname);
    }

    @Override
    public void addOrUpdate(final JsonObject info, final String uname) {
        this.primary.addOrUpdate(info, uname);
        try {
            this.secondary.addOrUpdate(info, uname);
        } catch (final Exception ignored) {
            // Best-effort: YAML write failure should not break DB operation
        }
    }

    @Override
    public void remove(final String uname) {
        this.primary.remove(uname);
        try {
            this.secondary.remove(uname);
        } catch (final Exception ignored) {
            // Best-effort
        }
    }

    @Override
    public void disable(final String uname) {
        this.primary.disable(uname);
    }

    @Override
    public void enable(final String uname) {
        this.primary.enable(uname);
    }

    @Override
    public void alterPassword(final String uname, final JsonObject info) {
        this.primary.alterPassword(uname, info);
        try {
            this.secondary.alterPassword(uname, info);
        } catch (final Exception ignored) {
            // Best-effort
        }
    }
}
