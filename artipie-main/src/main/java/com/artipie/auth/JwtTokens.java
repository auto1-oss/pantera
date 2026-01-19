/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.auth;

import com.artipie.api.AuthTokenRest;
import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.TokenAuthentication;
import com.artipie.http.auth.Tokens;
import com.artipie.settings.JwtSettings;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.jwt.JWTAuth;

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
}
