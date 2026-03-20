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
package com.auto1.pantera.http.auth;

import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.http.log.EcsLogger;

import java.security.Permission;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Operation controller for slice. The class is meant to check
 * if required permission is granted for user.
 * <p/>
 * Instances of this class are created in the adapter with users' policies and required
 * permission for the adapter's operation.
 */
public final class OperationControl {

    /**
     * Security policy.
     */
    private final Policy<?> policy;

    /**
     * Required permissions (at least one should be allowed).
     */
    private final Collection<Permission> perms;

    /**
     * Ctor.
     * @param policy Security policy
     * @param perm Required permission
     */
    public OperationControl(final Policy<?> policy, final Permission perm) {
        this(policy, Collections.singleton(perm));
    }

    /**
     * Ctor.
     * @param policy Security policy
     * @param perms Required permissions (at least one should be allowed)
     */
    public OperationControl(final Policy<?> policy, final Permission... perms) {
        this(policy, List.of(perms));
    }

    /**
     * Ctor.
     * @param policy Security policy
     * @param perms Required permissions (at least one should be allowed)
     */
    public OperationControl(final Policy<?> policy, final Collection<Permission> perms) {
        this.policy = policy;
        this.perms = perms;
    }

    /**
     * Check if user is authorized to perform an action.
     * @param user User name
     * @return True if authorized
     */
    public boolean allowed(final AuthUser user) {
        final boolean res = perms.stream()
            .anyMatch(perm -> policy.getPermissions(user).implies(perm));
        EcsLogger.debug("com.auto1.pantera.security")
            .message("Authorization operation")
            .eventCategory("security")
            .eventAction("authorization_check")
            .eventOutcome(res ? "success" : "failure")
            .field("user.name", user.name())
            .field("user.roles", this.perms.toString())
            .field("event.outcome", res ? "allowed" : "denied")
            .log();
        return res;
    }
}
