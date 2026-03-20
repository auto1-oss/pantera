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
package com.auto1.pantera.settings;

import java.util.Collection;
import javax.json.JsonObject;

/**
 * Create/Read/Update/Delete storages aliases settings.
 * @since 0.1
 */
public interface CrudStorageAliases {

    /**
     * List pantera storages.
     * @return Collection of {@link JsonObject} instances
     */
    Collection<JsonObject> list();

    /**
     * Add storage to pantera storages.
     * @param alias Storage alias
     * @param info Storage settings
     */
    void add(String alias, JsonObject info);

    /**
     * Remove storage from settings.
     * @param alias Storage alias
     */
    void remove(String alias);

}
