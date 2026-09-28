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

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import java.util.concurrent.CompletionStage;

/**
 * Conda download authentication: the anaconda {@code Authorization: token
 * <token>} header (which is also how a {@code /t/<token>/} URL token reaches
 * the repository) or HTTP Basic (conda with {@code .netrc} or credentials in
 * the channel URL).
 *
 * @since 2.2.9
 */
public final class TokenOrBasicAuthScheme implements AuthScheme {

    /**
     * Token scheme.
     */
    private final AuthScheme token;

    /**
     * Basic scheme.
     */
    private final AuthScheme basic;

    /**
     * Ctor.
     * @param users Basic authentication
     * @param tokens Token authentication
     */
    public TokenOrBasicAuthScheme(final Authentication users, final TokenAuthentication tokens) {
        this.token = new TokenAuthScheme(new TokenAuth(tokens));
        this.basic = new BasicAuthScheme(users);
    }

    @Override
    public CompletionStage<Result> authenticate(final Headers headers, final RequestLine line) {
        final boolean tkn = new RqHeaders(headers, Authorization.NAME).stream()
            .findFirst()
            .map(Authorization::new)
            .filter(Authorization::parseable)
            .map(atz -> TokenAuthScheme.NAME.equals(atz.scheme()))
            .orElse(false);
        final CompletionStage<Result> res;
        if (tkn) {
            res = this.token.authenticate(headers, line);
        } else {
            res = this.basic.authenticate(headers, line);
        }
        return res;
    }
}
