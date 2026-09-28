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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.users.CrudUsers;
import java.security.AllPermission;
import java.security.PermissionCollection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;

/**
 * Privilege ceiling for user-management writes (2.2.9, B14/B15).
 *
 * <p>{@code api_user_permissions} {@code update} / {@code change_password}
 * are delegated rights, not root. A caller without {@code all_permission}
 * may only:</p>
 * <ul>
 *   <li>modify (or reset the password of) a user who holds no
 *       {@code all_permission} and only roles the caller also holds — so a
 *       delegated manager cannot strip an administrator's roles, flip their
 *       auth provider or take over their account;</li>
 *   <li>assign roles the caller holds;</li>
 *   <li>set a password-format {@code type} ({@code plain}/{@code sha256}),
 *       never an identity-provider type.</li>
 * </ul>
 * Callers with {@code all_permission} are not limited. Methods answer a
 * refusal message, or {@code null} when the write is within the ceiling.
 * They read the user store and policy — call them off the event loop.
 *
 * @since 2.2.9
 */
final class UserWriteGuard {

    /**
     * Auth context for policy lookups of stored users.
     */
    private static final String LOCAL = "local";

    /**
     * User store.
     */
    private final CrudUsers users;

    /**
     * Security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param users User store
     * @param policy Security policy
     */
    UserWriteGuard(final CrudUsers users, final Policy<?> policy) {
        this.users = users;
        this.policy = policy;
    }

    /**
     * Whether the caller is an administrator (holds {@code all_permission}).
     * @param perms Caller's permissions
     * @return True for an administrator
     */
    boolean administrator(final PermissionCollection perms) {
        return perms.implies(new AllPermission());
    }

    /**
     * Ceiling on modifying an existing user.
     * @param caller Caller's username
     * @param perms Caller's permissions
     * @param target Target username
     * @return Refusal or {@code null}
     */
    String refuseTarget(final String caller, final PermissionCollection perms, final String target) {
        if (this.administrator(perms)) {
            return null;
        }
        if (this.policy.getPermissions(new AuthUser(target, UserWriteGuard.LOCAL))
            .implies(new AllPermission())) {
            return "Only an administrator can modify an administrator account";
        }
        final Set<String> own = this.roles(caller);
        for (final String role : this.roles(target)) {
            if (!own.contains(role)) {
                return "Cannot modify a user holding a role you do not hold yourself: " + role;
            }
        }
        return null;
    }

    /**
     * Ceiling on the roles a write assigns.
     * @param caller Caller's username
     * @param perms Caller's permissions
     * @param body Submitted user document
     * @return Refusal or {@code null}
     */
    String refuseRoles(final String caller, final PermissionCollection perms, final JsonObject body) {
        if (this.administrator(perms) || !body.containsKey("roles")) {
            return null;
        }
        final Set<String> own = this.roles(caller);
        for (final JsonValue role : body.getJsonArray("roles")) {
            final String name = ((JsonString) role).getString();
            if (!own.contains(name)) {
                return "Cannot assign a role you do not hold yourself: " + name;
            }
        }
        return null;
    }

    /**
     * Only an administrator may set an identity-provider {@code type}.
     * @param perms Caller's permissions
     * @param body Submitted user document
     * @return Refusal or {@code null}
     */
    String refuseProvider(final PermissionCollection perms, final JsonObject body) {
        if (this.administrator(perms) || !body.containsKey("type")) {
            return null;
        }
        final String type = body.getString("type", "");
        if ("plain".equals(type) || "sha256".equals(type)) {
            return null;
        }
        return "Only an administrator can set a user's authentication provider";
    }

    /**
     * Role names held by a stored user.
     * @param uname Username
     * @return Role names (empty for an unknown user)
     */
    private Set<String> roles(final String uname) {
        final Set<String> names = new HashSet<>();
        final Optional<JsonObject> user = this.users.get(uname);
        if (user.isPresent() && user.get().containsKey("roles")
            && user.get().get("roles").getValueType() == JsonValue.ValueType.ARRAY) {
            for (final JsonValue role : user.get().getJsonArray("roles")) {
                if (role instanceof JsonString str) {
                    names.add(str.getString());
                }
            }
        }
        return names;
    }
}
