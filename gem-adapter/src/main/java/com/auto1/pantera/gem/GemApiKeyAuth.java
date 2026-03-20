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
package com.auto1.pantera.gem;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;
import org.apache.commons.codec.binary.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@link AuthScheme} implementation for gem api key decoding.
 */
public final class GemApiKeyAuth implements AuthScheme {

    /**
     * Concrete implementation for User Identification.
     */
    private final Authentication auth;

    /**
     * Ctor.
     * @param auth Concrete implementation for User Identification.
     */
    public GemApiKeyAuth(final Authentication auth) {
        this.auth = auth;
    }

    @Override
    public CompletionStage<Result> authenticate(
        Headers headers, RequestLine line
    ) {
        return new RqHeaders(headers, Authorization.NAME).stream()
            .findFirst()
            .map(
                str -> {
                    final CompletionStage<Result> res;
                    if (str.startsWith(BasicAuthScheme.NAME)) {
                        res = new BasicAuthScheme(this.auth).authenticate(headers);
                    } else {
                        final String[] cred = new String(
                            Base64.decodeBase64(str.getBytes(StandardCharsets.UTF_8))
                        ).split(":");
                        if (cred.length < 2) {
                            res = CompletableFuture.completedFuture(
                                AuthScheme.result(AuthUser.ANONYMOUS, "")
                            );
                        } else {
                            final Optional<AuthUser> user = this.auth.user(
                                cred[0].trim(), cred[1].trim()
                            );
                            res = CompletableFuture.completedFuture(AuthScheme.result(user, ""));
                        }
                    }
                    return res;
                }
            )
            .orElse(
                CompletableFuture.completedFuture(
                    AuthScheme.result(AuthUser.ANONYMOUS, "")
                )
            );
    }
}
