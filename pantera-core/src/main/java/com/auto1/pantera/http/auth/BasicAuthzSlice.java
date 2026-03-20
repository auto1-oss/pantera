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
 * Slice with basic authentication.
 * @since 0.17
 */
public final class BasicAuthzSlice extends Slice.Wrap {

    /**
     * Ctor.
     * @param origin Origin slice
     * @param auth Authorization
     * @param control Access control
     */
    public BasicAuthzSlice(
        final Slice origin, final Authentication auth, final OperationControl control
    ) {
        super(new AuthzSlice(origin, new BasicAuthScheme(auth), control));
    }
}
