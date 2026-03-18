/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.perms;

import com.artipie.security.perms.Action;
import java.util.Collections;
import java.util.Set;

/**
 * Permissions to manage cooldown operations.
 * @since 1.21.0
 */
public final class ApiCooldownPermission extends RestApiPermission {

    /**
     * Permission name.
     */
    static final String NAME = "api_cooldown_permissions";

    /**
     * Required serial.
     */
    private static final long serialVersionUID = 7610976571453906973L;

    /**
     * Cooldown actions list.
     */
    private static final CooldownActionList ACTION_LIST = new CooldownActionList();

    /**
     * Read permission singleton.
     */
    public static final ApiCooldownPermission READ =
        new ApiCooldownPermission(CooldownAction.READ);

    /**
     * Write permission singleton.
     */
    public static final ApiCooldownPermission WRITE =
        new ApiCooldownPermission(CooldownAction.WRITE);

    /**
     * Ctor.
     * @param action Action
     */
    public ApiCooldownPermission(final CooldownAction action) {
        super(ApiCooldownPermission.NAME, action.mask, ApiCooldownPermission.ACTION_LIST);
    }

    /**
     * Ctor.
     * @param actions Actions set
     */
    public ApiCooldownPermission(final Set<String> actions) {
        super(
            ApiCooldownPermission.NAME,
            RestApiPermission.maskFromActions(actions, ApiCooldownPermission.ACTION_LIST),
            ApiCooldownPermission.ACTION_LIST
        );
    }

    @Override
    public ApiCooldownPermissionCollection newPermissionCollection() {
        return new ApiCooldownPermissionCollection();
    }

    /**
     * Collection of the cooldown permissions.
     * @since 1.21.0
     */
    static final class ApiCooldownPermissionCollection extends RestApiPermissionCollection {

        /**
         * Required serial.
         */
        private static final long serialVersionUID = -4010962571451212363L;

        /**
         * Ctor.
         */
        ApiCooldownPermissionCollection() {
            super(ApiCooldownPermission.class);
        }
    }

    /**
     * Cooldown actions.
     * @since 1.21.0
     */
    public enum CooldownAction implements Action {
        READ(0x4),
        WRITE(0x2),
        ALL(0x4 | 0x2);

        /**
         * Action mask.
         */
        private final int mask;

        /**
         * Ctor.
         * @param mask Mask int
         */
        CooldownAction(final int mask) {
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
     * Cooldown actions list.
     * @since 1.21.0
     */
    static final class CooldownActionList extends ApiActions {

        /**
         * Ctor.
         */
        CooldownActionList() {
            super(CooldownAction.values());
        }

        @Override
        public Action all() {
            return CooldownAction.ALL;
        }
    }
}
