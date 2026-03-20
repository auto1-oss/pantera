/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api.perms;

import com.auto1.pantera.security.perms.ArtipiePermissionFactory;
import com.auto1.pantera.security.perms.PermissionConfig;
import com.auto1.pantera.security.perms.PermissionFactory;

/**
 * Factory for {@link ApiRolePermission}.
 * @since 0.30
 */
@ArtipiePermissionFactory(ApiRolePermission.NAME)
public final class ApiRolePermissionFactory implements
    PermissionFactory<RestApiPermission.RestApiPermissionCollection> {

    @Override
    public RestApiPermission.RestApiPermissionCollection newPermissions(
        final PermissionConfig cfg
    ) {
        final ApiRolePermission perm = new ApiRolePermission(cfg.keys());
        final RestApiPermission.RestApiPermissionCollection collection =
            perm.newPermissionCollection();
        collection.add(perm);
        return collection;
    }
}
