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

/**
 * Permission factory to create permissions.
 * @param <T> Permission collection implementation
 * @since 1.2
 */
public interface PermissionFactory<T extends PermissionCollection> {

    /**
     * Create permissions collection.
     * @param config Configuration
     * @return Permission collection
     */
    T newPermissions(PermissionConfig config);
}
