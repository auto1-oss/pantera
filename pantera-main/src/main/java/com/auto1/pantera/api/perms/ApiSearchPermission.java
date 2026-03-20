/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api.perms;

import com.auto1.pantera.security.perms.Action;
import java.util.Collections;
import java.util.Set;

/**
 * Permissions to manage search operations.
 * @since 1.20.13
 */
public final class ApiSearchPermission extends RestApiPermission {

    /**
     * Permission name.
     */
    static final String NAME = "api_search_permissions";

    /**
     * Required serial.
     */
    private static final long serialVersionUID = 5610976571453906973L;

    /**
     * Search actions list.
     */
    private static final SearchActionList ACTION_LIST = new SearchActionList();

    /**
     * Read permission singleton.
     */
    public static final ApiSearchPermission READ =
        new ApiSearchPermission(SearchAction.READ);

    /**
     * Write permission singleton.
     */
    public static final ApiSearchPermission WRITE =
        new ApiSearchPermission(SearchAction.WRITE);

    /**
     * Ctor.
     * @param action Action
     */
    public ApiSearchPermission(final SearchAction action) {
        super(ApiSearchPermission.NAME, action.mask, ApiSearchPermission.ACTION_LIST);
    }

    /**
     * Ctor.
     * @param actions Actions set
     */
    public ApiSearchPermission(final Set<String> actions) {
        super(
            ApiSearchPermission.NAME,
            RestApiPermission.maskFromActions(actions, ApiSearchPermission.ACTION_LIST),
            ApiSearchPermission.ACTION_LIST
        );
    }

    @Override
    public ApiSearchPermissionCollection newPermissionCollection() {
        return new ApiSearchPermissionCollection();
    }

    /**
     * Collection of the search permissions.
     * @since 1.20.13
     */
    static final class ApiSearchPermissionCollection extends RestApiPermissionCollection {

        /**
         * Required serial.
         */
        private static final long serialVersionUID = -3010962571451212363L;

        /**
         * Ctor.
         */
        ApiSearchPermissionCollection() {
            super(ApiSearchPermission.class);
        }
    }

    /**
     * Search actions.
     * @since 1.20.13
     */
    public enum SearchAction implements Action {
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
        SearchAction(final int mask) {
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
     * Search actions list.
     * @since 1.20.13
     */
    static final class SearchActionList extends ApiActions {

        /**
         * Ctor.
         */
        SearchActionList() {
            super(SearchAction.values());
        }

        @Override
        public Action all() {
            return SearchAction.ALL;
        }
    }
}
