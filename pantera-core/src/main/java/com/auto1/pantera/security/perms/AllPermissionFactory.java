/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
