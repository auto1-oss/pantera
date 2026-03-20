/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
