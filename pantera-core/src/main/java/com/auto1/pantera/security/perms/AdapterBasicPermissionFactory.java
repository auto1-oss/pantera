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

/**
 * Factory for {@link AdapterBasicPermission}.
 * @since 1.2
 */
@PanteraPermissionFactory("adapter_basic_permissions")
public final class AdapterBasicPermissionFactory implements
    PermissionFactory<AdapterBasicPermission.AdapterBasicPermissionCollection> {

    @Override
    public AdapterBasicPermission.AdapterBasicPermissionCollection newPermissions(
        final PermissionConfig config
    ) {
        final AdapterBasicPermission.AdapterBasicPermissionCollection res =
            new AdapterBasicPermission.AdapterBasicPermissionCollection();
        for (final String name : config.keys()) {
            res.add(new AdapterBasicPermission(name, config.sequence(name)));
        }
        return res;
    }

}
