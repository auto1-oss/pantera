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
 * Slice with bearer token authorization.
 * @since 1.2
 */
public final class BearerAuthzSlice extends Slice.Wrap {

    /**
     * Creates bearer auth slice with {@link BearerAuthScheme} and empty challenge params.
     * @param origin Origin slice
     * @param auth Authorization
     * @param control Access control by permission
     */
    public BearerAuthzSlice(final Slice origin, final TokenAuthentication auth,
        final OperationControl control) {
        super(new AuthzSlice(origin, new BearerAuthScheme(auth, ""), control));
    }

    /**
     * Ctor.
     * @param origin Origin slice
     * @param scheme Bearer authentication scheme
     * @param control Access control by permission
     */
    public BearerAuthzSlice(final Slice origin, final BearerAuthScheme scheme,
        final OperationControl control) {
        super(new AuthzSlice(origin, scheme, control));
    }
}
