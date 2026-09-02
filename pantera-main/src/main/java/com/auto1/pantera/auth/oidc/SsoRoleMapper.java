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
package com.auto1.pantera.auth.oidc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps IdP groups to Pantera roles — DEFAULT DENY.
 *
 * <p>Before 2.2.9 a group without an explicit {@code group-roles} mapping
 * was used verbatim as the role name, so any IdP group named like a
 * privileged role (the bootstrap {@code admin}, for instance) granted that
 * role to whoever the IdP placed in the group. Only explicitly mapped
 * groups grant roles; when the token carries groups but none map, the
 * configured default role applies.</p>
 *
 * @since 2.2.9
 */
public final class SsoRoleMapper {

    private SsoRoleMapper() {
    }

    /**
     * Map groups to roles.
     *
     * @param groups Groups from the verified id_token
     * @param groupRoles Explicit group → role mapping from the provider config
     * @param defaultRole Role when groups are present but none mapped;
     *  {@code null}/empty to grant nothing
     * @return Roles to assign (deduplicated, in group order)
     */
    public static List<String> map(
        final List<String> groups,
        final Map<String, String> groupRoles,
        final String defaultRole
    ) {
        final List<String> roles = new ArrayList<>();
        for (final String group : groups) {
            final String mapped = groupRoles.get(group);
            if (mapped != null && !mapped.isEmpty() && !roles.contains(mapped)) {
                roles.add(mapped);
            }
        }
        if (roles.isEmpty() && !groups.isEmpty()
            && defaultRole != null && !defaultRole.isEmpty()) {
            roles.add(defaultRole);
        }
        return roles;
    }
}
