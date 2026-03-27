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
package com.auto1.pantera.conda.http.auth;

import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;

/**
 * Token authentication slice.
 * @since 0.5
 */
public final class TokenAuthSlice extends Slice.Wrap {

    /**
     * Ctor.
     * @param origin Origin slice
     * @param control Operation control
     * @param tokens Token authentication
     */
    public TokenAuthSlice(
        final Slice origin, final OperationControl control, final TokenAuthentication tokens
    ) {
        super(new AuthzSlice(origin, new TokenAuthScheme(new TokenAuth(tokens)), control));
    }
}
