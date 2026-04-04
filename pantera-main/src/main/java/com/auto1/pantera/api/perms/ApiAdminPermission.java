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
package com.auto1.pantera.api.perms;

import com.auto1.pantera.security.perms.Action;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Permissions for system administration operations: auth settings,
 * user revocation, and other server-wide actions.
 *
 * @since 2.1.0
 */
public final class ApiAdminPermission extends RestApiPermission {

    /**
     * Permission name.
     */
    static final String NAME = "api_admin_permissions";

    /**
     * Required serial.
     */
    private static final long serialVersionUID = 7210976571453906975L;

    /**
     * Admin actions list.
     */
    private static final AdminActionList ACTION_LIST = new AdminActionList();

    /**
     * Singleton for admin-level access.
     */
    public static final ApiAdminPermission ADMIN =
        new ApiAdminPermission(AdminAction.ADMIN);

    /**
     * Ctor.
     * @param action Action
     */
    public ApiAdminPermission(final AdminAction action) {
        super(ApiAdminPermission.NAME, action.mask, ApiAdminPermission.ACTION_LIST);
    }

    /**
     * Ctor.
     * @param actions Actions set
     */
    public ApiAdminPermission(final Set<String> actions) {
        super(
            ApiAdminPermission.NAME,
            RestApiPermission.maskFromActions(actions, ApiAdminPermission.ACTION_LIST),
            ApiAdminPermission.ACTION_LIST
        );
    }

    @Override
    public ApiAdminPermissionCollection newPermissionCollection() {
        return new ApiAdminPermissionCollection();
    }

    /**
     * Collection of admin permissions.
     */
    static final class ApiAdminPermissionCollection extends RestApiPermissionCollection {

        private static final long serialVersionUID = -3010962571451212365L;

        ApiAdminPermissionCollection() {
            super(ApiAdminPermission.class);
        }
    }

    /**
     * Admin actions.
     */
    public enum AdminAction implements Action {
        /**
         * Full admin access.
         */
        ADMIN(0x1),
        /**
         * All admin actions (alias).
         */
        ALL(0x1);

        private final int mask;

        AdminAction(final int mask) {
            this.mask = mask;
        }

        @Override
        public Set<String> names() {
            return Collections.singleton(this.name().toLowerCase(Locale.ROOT));
        }

        @Override
        public int mask() {
            return this.mask;
        }
    }

    /**
     * Admin actions list.
     */
    static final class AdminActionList extends ApiActions {

        AdminActionList() {
            super(AdminAction.values());
        }

        @Override
        public Action all() {
            return AdminAction.ALL;
        }
    }
}
