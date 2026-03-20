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
package com.auto1.pantera.security.perms;

import java.security.PermissionCollection;
import java.util.Collection;
import java.util.Collections;

/**
 * User provides its individual permission collection and
 * groups.
 * @since 1.2
 */
public interface User {

    /**
     * Empty user with no permissions and no roles.
     */
    User EMPTY = new User() {
        @Override
        public Collection<String> roles() {
            return Collections.emptyList();
        }

        @Override
        public PermissionCollection perms() {
            return EmptyPermissions.INSTANCE;
        }
    };

    /**
     * Returns user groups.
     * @return Collection of the groups
     */
    Collection<String> roles();

    /**
     * Returns user's individual permissions.
     * @return Individual permissions collection
     */
    PermissionCollection perms();

}
