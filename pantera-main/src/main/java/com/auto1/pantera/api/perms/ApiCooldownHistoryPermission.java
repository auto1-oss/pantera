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
import java.util.Set;

/**
 * Permissions to read the cooldown archival history feed
 * ({@code GET /api/v1/cooldown/history}).
 *
 * <p>History is a distinct, optional view — a user with
 * {@link ApiCooldownPermission#READ} can see the live blocked list, but not
 * necessarily the long-term archive. Operators can grant this separately to
 * constrain exposure of who-unblocked-what metadata. The endpoint still
 * enforces per-repo {@code AdapterBasicPermission(repo, "read")} on top, so
 * this permission is the coarse API-level gate and the repo permission is
 * the fine-grained row filter.
 *
 * @since 2.2.0
 */
public final class ApiCooldownHistoryPermission extends RestApiPermission {

    /**
     * Permission name.
     */
    static final String NAME = "api_cooldown_history_permissions";

    /**
     * Required serial.
     */
    private static final long serialVersionUID = 7610976571453906974L;

    /**
     * Cooldown history actions list.
     */
    private static final CooldownHistoryActionList ACTION_LIST = new CooldownHistoryActionList();

    /**
     * Read permission singleton.
     */
    public static final ApiCooldownHistoryPermission READ =
        new ApiCooldownHistoryPermission(CooldownHistoryAction.READ);

    /**
     * Ctor.
     * @param action Action
     */
    public ApiCooldownHistoryPermission(final CooldownHistoryAction action) {
        super(
            ApiCooldownHistoryPermission.NAME, action.mask,
            ApiCooldownHistoryPermission.ACTION_LIST
        );
    }

    /**
     * Ctor.
     * @param actions Actions set
     */
    public ApiCooldownHistoryPermission(final Set<String> actions) {
        super(
            ApiCooldownHistoryPermission.NAME,
            RestApiPermission.maskFromActions(
                actions, ApiCooldownHistoryPermission.ACTION_LIST
            ),
            ApiCooldownHistoryPermission.ACTION_LIST
        );
    }

    @Override
    public ApiCooldownHistoryPermissionCollection newPermissionCollection() {
        return new ApiCooldownHistoryPermissionCollection();
    }

    /**
     * Collection of the cooldown history permissions.
     * @since 2.2.0
     */
    static final class ApiCooldownHistoryPermissionCollection extends RestApiPermissionCollection {

        /**
         * Required serial.
         */
        private static final long serialVersionUID = -4010962571451212364L;

        /**
         * Ctor.
         */
        ApiCooldownHistoryPermissionCollection() {
            super(ApiCooldownHistoryPermission.class);
        }
    }

    /**
     * Cooldown history actions. Read-only feed — no write action exists.
     * @since 2.2.0
     */
    public enum CooldownHistoryAction implements Action {
        READ(0x4),
        ALL(0x4);

        /**
         * Action mask.
         */
        private final int mask;

        /**
         * Ctor.
         * @param mask Mask int
         */
        CooldownHistoryAction(final int mask) {
            this.mask = mask;
        }

        @Override
        public Set<String> names() {
            return Collections.singleton(this.name().toLowerCase(java.util.Locale.ROOT));
        }

        @Override
        public int mask() {
            return this.mask;
        }
    }

    /**
     * Cooldown history actions list.
     * @since 2.2.0
     */
    static final class CooldownHistoryActionList extends ApiActions {

        /**
         * Ctor.
         */
        CooldownHistoryActionList() {
            super(CooldownHistoryAction.values());
        }

        @Override
        public Action all() {
            return CooldownHistoryAction.ALL;
        }
    }
}
