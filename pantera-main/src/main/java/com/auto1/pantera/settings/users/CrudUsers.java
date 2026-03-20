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
 * Create/Read/Update/Delete Pantera users.
 * @since 0.27
 */
public interface CrudUsers {
    /**
     * List existing users.
     * @return Pantera users
     */
    JsonArray list();

    /**
     * Get user info.
     * @param uname Username
     * @return User info if user is found
     */
    Optional<JsonObject> get(String uname);

    /**
     * Add user.
     * @param info User info (password, email, groups, etc)
     * @param uname User name
     */
    void addOrUpdate(JsonObject info, String uname);

    /**
     * Disable user by name.
     * @param uname User name
     */
    void disable(String uname);

    /**
     * Enable user by name.
     * @param uname User name
     */
    void enable(String uname);

    /**
     * Remove user by name.
     * @param uname User name
     */
    void remove(String uname);

    /**
     * Alter user's password.
     * @param uname Username
     * @param info Json object with new password and type
     */
    void alterPassword(String uname, JsonObject info);

}
