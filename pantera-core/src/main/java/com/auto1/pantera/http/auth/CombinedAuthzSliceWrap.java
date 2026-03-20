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

import com.auto1.pantera.http.Slice;

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
