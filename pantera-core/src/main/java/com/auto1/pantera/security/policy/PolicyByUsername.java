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
import com.auto1.pantera.security.perms.EmptyPermissions;
import com.auto1.pantera.security.perms.FreePermissions;
import java.security.PermissionCollection;

/**
 * Policy implementation for test: returns {@link FreePermissions} for
 * given name and {@link EmptyPermissions} for any other user.
 * @since 1.2
 */
public final class PolicyByUsername implements Policy<PermissionCollection> {

    /**
     * Username.
     */
    private final String name;

    /**
     * Ctor.
     * @param name Username
     */
    public PolicyByUsername(final String name) {
        this.name = name;
    }

    @Override
    public PermissionCollection getPermissions(final AuthUser user) {
        final PermissionCollection res;
        if (this.name.equals(user.name())) {
            res = new FreePermissions();
        } else {
            res = EmptyPermissions.INSTANCE;
        }
        return res;
    }
}
