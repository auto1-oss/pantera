/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
