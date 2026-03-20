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
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.settings.JwtSettings;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.util.UUID;

/**
 * Implementation to manage JWT tokens.
 * @since 0.29
 */
public final class JwtTokens implements Tokens {

    /**
     * Jwt auth provider.
     */
    private final JWTAuth provider;

    /**
     * JWT options for token generation.
     */
    private final JWTOptions options;

    /**
     * Ctor with default options (permanent tokens).
     * @param provider Jwt auth provider
     */
    public JwtTokens(final JWTAuth provider) {
        this(provider, new JwtSettings());
    }

    /**
     * Ctor with JWT settings.
     * @param provider Jwt auth provider
     * @param settings JWT settings
     */
    public JwtTokens(final JWTAuth provider, final JwtSettings settings) {
        this.provider = provider;
        this.options = new JWTOptions();
        if (settings.expires()) {
            this.options.setExpiresInSeconds(settings.expirySeconds());
        }
    }

    @Override
    public TokenAuthentication auth() {
        return new JwtTokenAuth(this.provider);
    }

    @Override
    public String generate(final AuthUser user) {
        return this.provider.generateToken(
            new JsonObject().put(AuthTokenRest.SUB, user.name())
                .put(AuthTokenRest.CONTEXT, user.authContext()),
            this.options
        );
    }

    @Override
    public String generate(final AuthUser user, final boolean permanent) {
        final JWTOptions opts = permanent ? new JWTOptions() : this.options;
        return this.provider.generateToken(
            new JsonObject().put(AuthTokenRest.SUB, user.name())
                .put(AuthTokenRest.CONTEXT, user.authContext()),
            opts
        );
    }

    /**
     * Generate token with a specific expiry and token ID for revocation support.
     * @param user User to issue token for
     * @param expirySeconds Expiry in seconds (0 or negative = permanent)
     * @param jti Unique token ID for tracking/revocation
     * @return String token
     */
    public String generate(final AuthUser user, final int expirySeconds, final UUID jti) {
        final JWTOptions opts = new JWTOptions();
        if (expirySeconds > 0) {
            opts.setExpiresInSeconds(expirySeconds);
        }
        return this.provider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, user.name())
                .put(AuthTokenRest.CONTEXT, user.authContext())
                .put("jti", jti.toString()),
            opts
        );
    }
}
