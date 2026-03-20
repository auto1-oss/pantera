/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings.users;

import java.util.Optional;
import javax.json.JsonArray;
import javax.json.JsonObject;

/**
 * Create/Read/Update/Delete Pantera roles.
 * @since 0.27
 */
public interface CrudRoles {
    /**
     * List existing roles.
     * @return Pantera roles
     */
    JsonArray list();

    /**
     * Get role info.
     * @param rname Role name
     * @return Role info if role is found
     */
    Optional<JsonObject> get(String rname);

    /**
     * Add role.
     * @param info Role info (the set of permissions)
     * @param rname Role name
     */
    void addOrUpdate(JsonObject info, String rname);

    /**
     * Disable role by name.
     * @param rname Role name
     */
    void disable(String rname);

    /**
     * Enable role by name.
     * @param rname Role name
     */
    void enable(String rname);

    /**
     * Remove role by name.
     * @param rname Role name
     */
    void remove(String rname);

}
