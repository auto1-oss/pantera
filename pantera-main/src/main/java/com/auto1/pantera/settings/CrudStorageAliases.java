/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
