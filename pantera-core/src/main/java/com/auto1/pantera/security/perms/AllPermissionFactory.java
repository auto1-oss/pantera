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

import java.security.AllPermission;
import java.security.PermissionCollection;

/**
 * Permission factory for {@link AllPermission}.
 * @since 1.2
 */
@PanteraPermissionFactory("all_permission")
public final class AllPermissionFactory implements PermissionFactory<PermissionCollection> {

    @Override
    public PermissionCollection newPermissions(final PermissionConfig config) {
        final AllPermission all = new AllPermission();
        final PermissionCollection collection = all.newPermissionCollection();
        collection.add(all);
        return collection;
    }

}
