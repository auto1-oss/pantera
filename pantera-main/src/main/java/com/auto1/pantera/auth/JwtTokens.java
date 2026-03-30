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
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.settings.JwtSettings;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.time.Instant;
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
     * JWT options for token generation (session tokens).
     */
    private final JWTOptions options;

    /**
     * Expiry in seconds for session tokens; 0 means permanent.
     */
    private final int expirySeconds;

    /**
     * Token DAO for persisting JTIs. Null in YAML-only (no-DB) mode.
     */
    private final UserTokenDao tokenDao;

    /**
     * Ctor with default options (permanent tokens, no JTI enforcement).
     * @param provider Jwt auth provider
     */
    public JwtTokens(final JWTAuth provider) {
        this(provider, new JwtSettings(), null);
    }

    /**
     * Ctor with JWT settings (no JTI enforcement — legacy / no-DB mode).
     * @param provider Jwt auth provider
     * @param settings JWT settings
     */
    public JwtTokens(final JWTAuth provider, final JwtSettings settings) {
        this(provider, settings, null);
    }

    /**
     * Ctor with JWT settings and token DAO.
     * When {@code tokenDao} is non-null every generated token embeds a {@code jti}
     * UUID that is stored in {@code user_tokens}, and every presented token must
     * have its JTI validated against that table.
     *
     * @param provider Jwt auth provider
     * @param settings JWT settings
     * @param tokenDao DAO for JTI persistence; {@code null} disables JTI enforcement
     */
    public JwtTokens(final JWTAuth provider, final JwtSettings settings,
        final UserTokenDao tokenDao) {
        this.provider = provider;
        this.tokenDao = tokenDao;
        this.expirySeconds = settings.expires() ? settings.expirySeconds() : 0;
        this.options = new JWTOptions();
        if (settings.expires()) {
            this.options.setExpiresInSeconds(settings.expirySeconds());
        }
    }

    @Override
    public TokenAuthentication auth() {
        return new JwtTokenAuth(this.provider, this.tokenDao);
    }

    @Override
    public String generate(final AuthUser user) {
        return this.issue(user, this.options, this.expirySeconds);
    }

    @Override
    public String generate(final AuthUser user, final boolean permanent) {
        final JWTOptions opts = permanent ? new JWTOptions() : this.options;
        return this.issue(user, opts, permanent ? 0 : this.expirySeconds);
    }

    /**
     * Generate token with a specific expiry and token ID for revocation support.
     * When a DAO is present the JTI is persisted so the token can be validated and revoked.
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
        final String token = this.provider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, user.name())
                .put(AuthTokenRest.CONTEXT, user.authContext())
                .put("jti", jti.toString()),
            opts
        );
        if (this.tokenDao != null) {
            final Instant exp = expirySeconds > 0 ? Instant.now().plusSeconds(expirySeconds) : null;
            this.tokenDao.store(jti, user.name(), "API Token", token, exp);
        }
        return token;
    }

    /**
     * Shared token issuance: always embeds a jti and stores it when a DAO is present.
     */
    private String issue(final AuthUser user, final JWTOptions opts, final int expSecs) {
        final UUID jti = UUID.randomUUID();
        final String token = this.provider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, user.name())
                .put(AuthTokenRest.CONTEXT, user.authContext())
                .put("jti", jti.toString()),
            opts
        );
        if (this.tokenDao != null) {
            final Instant exp = expSecs > 0 ? Instant.now().plusSeconds(expSecs) : null;
            this.tokenDao.store(jti, user.name(), "API Token", token, exp);
        }
        return token;
    }
}
