/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.auth;

import com.artipie.http.Slice;

/**
 * Slice with combined basic and bearer token authentication.
 * Supports both Basic and Bearer authentication methods.
 * @since 1.18
 */
public final class CombinedAuthzSliceWrap extends Slice.Wrap {

    /**
     * Ctor.
     * @param origin Origin slice
     * @param basicAuth Basic authentication
     * @param tokenAuth Token authentication
     * @param control Access control
     */
    public CombinedAuthzSliceWrap(
        final Slice origin,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth,
        final OperationControl control
    ) {
        super(new CombinedAuthzSlice(origin, basicAuth, tokenAuth, control));
    }
}
