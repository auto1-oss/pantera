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
package com.auto1.pantera.security.policy;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.perms.FreePermissions;
import java.security.PermissionCollection;

/**
 * Security policy.
 *
 * @param <P> Implementation of {@link PermissionCollection}
 * @since 1.2
 */
public interface Policy<P extends PermissionCollection> {

    /**
     * Free policy for any user returns {@link FreePermissions} which implies any permission.
     */
    Policy<PermissionCollection> FREE = user -> new FreePermissions();

    /**
     * Get collection of permissions {@link PermissionCollection} for user by username.
     * <p>
     * Each user can have permissions of various types, for example:
     * list of {@link AdapterBasicPermission} for adapter with basic permissions and
     * another permissions' implementation for docker adapter.
     *
     * @param user User
     * @return Set of {@link PermissionCollection}
     */
    P getPermissions(AuthUser user);

}
