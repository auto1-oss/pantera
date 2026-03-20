/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.perms;

import com.artipie.security.perms.ArtipiePermissionFactory;
import com.artipie.security.perms.PermissionConfig;
import com.artipie.security.perms.PermissionFactory;

/**
 * Factory for {@link ApiSearchPermission}.
 * @since 1.20.13
 */
@ArtipiePermissionFactory(ApiSearchPermission.NAME)
public final class ApiSearchPermissionFactory implements
    PermissionFactory<RestApiPermission.RestApiPermissionCollection> {

    @Override
    public RestApiPermission.RestApiPermissionCollection newPermissions(
        final PermissionConfig cfg
    ) {
        final ApiSearchPermission perm = new ApiSearchPermission(cfg.keys());
        final RestApiPermission.RestApiPermissionCollection collection =
            perm.newPermissionCollection();
        collection.add(perm);
        return collection;
    }
}
