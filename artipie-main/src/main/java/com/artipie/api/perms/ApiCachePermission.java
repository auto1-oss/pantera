/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.perms;

import com.artipie.security.perms.Action;
import java.util.Collections;
import java.util.Set;

/**
 * Permissions to manage cache operations.
 * @since 1.0
 */
public final class ApiCachePermission extends RestApiPermission {

    /**
     * Permission name.
     */
    static final String NAME = "api_cache_permissions";

    /**
     * Required serial.
     */
    private static final long serialVersionUID = 7810976571453906972L;

    /**
     * Cache actions list.
     */
    private static final CacheActionList ACTION_LIST = new CacheActionList();

    /**
     * Read permission singleton.
     */
    public static final ApiCachePermission READ = new ApiCachePermission(CacheAction.READ);

    /**
     * Write permission singleton.
     */
    public static final ApiCachePermission WRITE = new ApiCachePermission(CacheAction.WRITE);

    /**
     * Ctor.
     * @param action Action
     */
    public ApiCachePermission(final CacheAction action) {
        super(ApiCachePermission.NAME, action.mask, ApiCachePermission.ACTION_LIST);
    }

    /**
     * Ctor.
     * @param actions Actions set
     */
    public ApiCachePermission(final Set<String> actions) {
        super(
            ApiCachePermission.NAME,
            RestApiPermission.maskFromActions(actions, ApiCachePermission.ACTION_LIST),
            ApiCachePermission.ACTION_LIST
        );
    }

    @Override
    public ApiCachePermissionCollection newPermissionCollection() {
        return new ApiCachePermissionCollection();
    }

    /**
     * Collection of the cache permissions.
     * @since 1.0
     */
    static final class ApiCachePermissionCollection extends RestApiPermissionCollection {

        /**
         * Required serial.
         */
        private static final long serialVersionUID = -2010962571451212362L;

        /**
         * Ctor.
         */
        ApiCachePermissionCollection() {
            super(ApiCachePermission.class);
        }
    }

    /**
     * Cache actions.
     * @since 1.0
     */
    public enum CacheAction implements Action {
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
        CacheAction(final int mask) {
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
     * Cache actions list.
     * @since 1.0
     */
    static final class CacheActionList extends ApiActions {

        /**
         * Ctor.
         */
        CacheActionList() {
            super(CacheAction.values());
        }

        @Override
        public Action all() {
            return CacheAction.ALL;
        }
    }
}
