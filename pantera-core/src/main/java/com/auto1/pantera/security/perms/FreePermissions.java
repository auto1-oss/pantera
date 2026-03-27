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

import java.security.Permission;
import java.security.PermissionCollection;
import java.util.Collections;
import java.util.Enumeration;
import org.apache.commons.lang3.NotImplementedException;

/**
 * Free permissions implies any permission.
 * @since 1.2
 */
public final class FreePermissions extends PermissionCollection {

    /**
     * Class instance.
     */
    public static final PermissionCollection INSTANCE = new FreePermissions();

    /**
     * Required serial.
     */
    private static final long serialVersionUID = 1346496579871236952L;

    @Override
    public void add(final Permission permission) {
        throw new NotImplementedException(
            "This permission collection does not support adding elements"
        );
    }

    @Override
    public boolean implies(final Permission permission) {
        return true;
    }

    @Override
    public Enumeration<Permission> elements() {
        return Collections.emptyEnumeration();
    }
}
