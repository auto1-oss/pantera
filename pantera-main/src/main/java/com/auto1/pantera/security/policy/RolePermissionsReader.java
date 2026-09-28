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

import java.security.PermissionCollection;
import javax.json.JsonObject;

/**
 * Materialises a role document (the JSON persisted in {@code roles.permissions})
 * into the same {@link PermissionCollection} the policy would grant a holder
 * of that role — using exactly the factories {@link CachedDbPolicy} uses.
 *
 * <p>Used by the role-authoring API to enforce a privilege ceiling
 * (2.2.9, privesc-role): a caller may only grant permissions that their
 * own effective permission set already implies, evaluated on the REAL
 * materialised permissions rather than on the raw JSON.</p>
 *
 * @since 2.2.9
 */
public final class RolePermissionsReader {

    /**
     * Materialise a role document.
     * @param role Role JSON (either the {@code permissions} object itself or
     *  an object wrapping it under {@code "permissions"})
     * @return The permissions the role grants
     */
    public PermissionCollection read(final JsonObject role) {
        return CachedDbPolicy.readPermissionsFromJson(role);
    }
}
