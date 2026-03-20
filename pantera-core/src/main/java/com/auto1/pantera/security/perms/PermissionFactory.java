/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.security.perms;

import java.security.PermissionCollection;

/**
 * Permission factory to create permissions.
 * @param <T> Permission collection implementation
 * @since 1.2
 */
public interface PermissionFactory<T extends PermissionCollection> {

    /**
     * Create permissions collection.
     * @param config Configuration
     * @return Permission collection
     */
    T newPermissions(PermissionConfig config);
}
