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
 * Permissions to manage storage aliases.
 * @since 0.30
 */
public final class ApiAliasPermission extends RestApiPermission {

    /**
     * Permission name.
     */
    static final String NAME = "api_storage_alias_permissions";

    /**
     * Required serial.
     */
    private static final long serialVersionUID = -2910962571451212361L;

    /**
     * Alias actions list.
     */
    private static final AliasActionList ACTION_LIST = new AliasActionList();

    /**
     * Ctor.
     * @param action Action
     */
    public ApiAliasPermission(final AliasAction action) {
        super(ApiAliasPermission.NAME, action.mask, ApiAliasPermission.ACTION_LIST);
    }

    /**
     * Ctor.
     * @param actions Actions set
     */
    public ApiAliasPermission(final Set<String> actions) {
        super(
            ApiAliasPermission.NAME,
            ApiAliasPermission.maskFromActions(actions, ApiAliasPermission.ACTION_LIST),
            ApiAliasPermission.ACTION_LIST
        );
    }

    @Override
    public ApiAliasPermissionCollection newPermissionCollection() {
        return new ApiAliasPermissionCollection();
    }

    /**
     * Collection of the alias permissions.
     * @since 0.30
     */
    static final class ApiAliasPermissionCollection extends RestApiPermissionCollection {

        /**
         * Required serial.
         */
        private static final long serialVersionUID = 3010962571451212361L;

        /**
         * Ctor.
         */
        ApiAliasPermissionCollection() {
            super(ApiAliasPermission.class);
        }
    }

    /**
     * Alias actions.
     * @since 0.29
     */
    public enum AliasAction implements Action {
        READ(0x4),
        CREATE(0x2),
        DELETE(0x8),
        ALL(0x4 | 0x2 | 0x8);

        /**
         * Action mask.
         */
        private final int mask;

        /**
         * Ctor.
         * @param mask Mask int
         */
        AliasAction(final int mask) {
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
     * Manage aliases actions list.
     * @since 0.30
     */
    static final class AliasActionList extends ApiActions {

        /**
         * Ctor.
         */
        AliasActionList() {
            super(AliasAction.values());
        }

        @Override
        public Action all() {
            return AliasAction.ALL;
        }

    }
}
