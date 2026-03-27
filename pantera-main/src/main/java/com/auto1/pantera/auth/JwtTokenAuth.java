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
package com.auto1.pantera.auth;

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Token authentication with Vert.x {@link io.vertx.ext.auth.jwt.JWTAuth} under the hood.
 */
public final class JwtTokenAuth implements TokenAuthentication {

    /**
     * Jwt auth provider.
     */
    private final JWTAuth provider;

    /**
     * @param provider Jwt auth provider
     */
    public JwtTokenAuth(JWTAuth provider) {
        this.provider = provider;
    }

    @Override
    public CompletionStage<Optional<AuthUser>> user(String token) {
        return this.provider
            .authenticate(new TokenCredentials(token))
            .map(
                user -> {
                    Optional<AuthUser> res = Optional.empty();
                    if (user.principal().containsKey(AuthTokenRest.SUB)
                        && user.principal().containsKey(AuthTokenRest.CONTEXT)) {
                        res = Optional.of(
                            new AuthUser(
                                user.principal().getString(AuthTokenRest.SUB),
                                user.principal().getString(AuthTokenRest.CONTEXT)
                            )
                        );
                    }
                    return res;
                }
            ).otherwise(Optional.empty())
            .toCompletionStage();
    }
}
