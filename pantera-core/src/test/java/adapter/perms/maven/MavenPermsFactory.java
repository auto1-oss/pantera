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
package adapter.perms.maven;

import com.auto1.pantera.security.perms.PanteraPermissionFactory;
import com.auto1.pantera.security.perms.PermissionConfig;
import com.auto1.pantera.security.perms.PermissionFactory;
import java.security.AllPermission;
import java.security.PermissionCollection;

/**
 * Test permission.
 * @since 1.2
 */
@PanteraPermissionFactory("maven-perm")
public final class MavenPermsFactory implements PermissionFactory<PermissionCollection> {
    @Override
    public PermissionCollection newPermissions(final PermissionConfig config) {
        return new AllPermission().newPermissionCollection();
    }
}
