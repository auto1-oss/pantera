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
package com.auto1.pantera.api.perms;

import com.auto1.pantera.security.perms.PanteraPermissionFactory;
import com.auto1.pantera.security.perms.PermissionConfig;
import com.auto1.pantera.security.perms.PermissionFactory;

/**
 * Factory for {@link ApiRepositoryPermission}.
 * @since 0.30
 */
@PanteraPermissionFactory(ApiRepositoryPermission.NAME)
public final class ApiRepositoryPermissionFactory implements
    PermissionFactory<RestApiPermission.RestApiPermissionCollection> {

    @Override
    public RestApiPermission.RestApiPermissionCollection newPermissions(
        final PermissionConfig cfg
    ) {
        final ApiRepositoryPermission perm = new ApiRepositoryPermission(cfg.keys());
        final RestApiPermission.RestApiPermissionCollection collection =
            perm.newPermissionCollection();
        collection.add(perm);
        return collection;
    }
}
