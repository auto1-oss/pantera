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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.security.policy.Policy;

import java.util.concurrent.CompletableFuture;

/**
 * Slice that implements authorization for {@link ScopeSlice}.
 */
final class AuthScopeSlice implements Slice {

    /**
     * Origin.
     */
    private final ScopeSlice origin;

    /**
     * Authentication scheme.
     */
    private final AuthScheme auth;

    /**
     * Access permissions.
     */
    private final Policy<?> policy;

    /**
     * @param origin Origin slice.
     * @param auth Authentication scheme.
     * @param policy Access permissions.
     */
    AuthScopeSlice(ScopeSlice origin, AuthScheme auth, Policy<?> policy) {
        this.origin = origin;
        this.auth = auth;
        this.policy = policy;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return new AuthzSlice(
            this.origin,
            this.auth,
            new OperationControl(this.policy, this.origin.permission(line))
        ).response(line, headers, body);
    }
}
