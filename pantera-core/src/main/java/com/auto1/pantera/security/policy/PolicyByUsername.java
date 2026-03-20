/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
