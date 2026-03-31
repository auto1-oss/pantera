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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;

/**
 * Implementation to manage JWT tokens using Auth0 java-jwt with RS256 signing.
 * @since 2.1.0
 */
public final class JwtTokens implements Tokens {

    /**
     * RS256 signing algorithm (wraps RSA key pair).
     */
    private final Algorithm algorithm;

    /**
     * RSA public key (exposed for auth handler construction in Task 11).
     */
    private final RSAPublicKey publicKey;

    /**
     * Token DAO for persisting JTIs. Null in no-DB mode.
     */
    private final UserTokenDao tokenDao;

    /**
     * Auth settings DAO for TTL configuration. Null in no-DB mode.
     */
    private final AuthSettingsDao settingsDao;

    /**
     * Revocation blocklist for token invalidation.
     */
    private final RevocationBlocklist blocklist;

    /**
     * Default access token TTL in seconds (cached from settings on construction).
     */
    private final int defaultAccessTtl;

    /**
     * Default refresh token TTL in seconds (cached from settings on construction).
     */
    private final int defaultRefreshTtl;

    /**
     * Ctor.
     * @param privateKey RSA private key for signing
     * @param publicKey RSA public key for verification
     * @param tokenDao DAO for JTI persistence; {@code null} disables JTI enforcement
     * @param settingsDao DAO for TTL configuration; {@code null} uses defaults
     * @param blocklist Revocation blocklist; {@code null} disables revocation checks
     */
    public JwtTokens(
        final RSAPrivateKey privateKey,
        final RSAPublicKey publicKey,
        final UserTokenDao tokenDao,
        final AuthSettingsDao settingsDao,
        final RevocationBlocklist blocklist
    ) {
        this.algorithm = Algorithm.RSA256(publicKey, privateKey);
        this.publicKey = publicKey;
        this.tokenDao = tokenDao;
        this.settingsDao = settingsDao;
        this.blocklist = blocklist;
        this.defaultAccessTtl = settingsDao != null
            ? settingsDao.getInt("access_token_ttl_seconds", 3600) : 3600;
        this.defaultRefreshTtl = settingsDao != null
            ? settingsDao.getInt("refresh_token_ttl_seconds", 604800) : 604800;
    }

    @Override
    public TokenAuthentication auth() {
        return new UnifiedJwtAuthHandler(this.publicKey, this.tokenDao, this.blocklist);
    }

    @Override
    public String generate(final AuthUser user) {
        return this.generateAccess(user);
    }

    @Override
    public String generate(final AuthUser user, final boolean permanent) {
        if (permanent) {
            return this.generateApiToken(user, 0, UUID.randomUUID(), "API Token");
        }
        return this.generateAccess(user);
    }

    @Override
    public Tokens.TokenPair generatePair(final AuthUser user) {
        final String access = this.generateAccess(user);
        final String refresh = this.generateRefresh(user);
        final int ttl = this.settingsDao != null
            ? this.settingsDao.getInt("access_token_ttl_seconds", 3600)
            : this.defaultAccessTtl;
        return new Tokens.TokenPair(access, refresh, ttl);
    }

    /**
     * Generate a named API token with a specific expiry and JTI.
     * When a DAO is present the JTI is persisted so the token can be validated and revoked.
     * @param user User to issue token for
     * @param expirySeconds Expiry in seconds (0 or negative = permanent)
     * @param jti Unique token ID for tracking/revocation
     * @param label Human-readable label for the token
     * @return Signed JWT string
     */
    public String generateApiToken(
        final AuthUser user, final int expirySeconds,
        final UUID jti, final String label
    ) {
        final var builder = JWT.create()
            .withSubject(user.name())
            .withClaim(AuthTokenRest.CONTEXT, user.authContext())
            .withClaim(AuthTokenRest.TYPE, TokenType.API.value())
            .withJWTId(jti.toString())
            .withIssuedAt(Instant.now());
        final Instant expiresAt;
        if (expirySeconds > 0) {
            expiresAt = Instant.now().plusSeconds(expirySeconds);
            builder.withExpiresAt(expiresAt);
        } else {
            expiresAt = null;
        }
        final String token = builder.sign(this.algorithm);
        if (this.tokenDao != null) {
            this.tokenDao.store(jti, user.name(), label, token, expiresAt, "api");
        }
        return token;
    }

    /**
     * Expose the RSA public key for auth handler construction in Task 11.
     * @return RSA public key
     */
    public RSAPublicKey publicKey() {
        return this.publicKey;
    }

    /**
     * Expose the token DAO for auth handler use in Task 11.
     * @return UserTokenDao, may be null
     */
    public UserTokenDao tokenDao() {
        return this.tokenDao;
    }

    /**
     * Expose the revocation blocklist for auth handler use in Task 11.
     * @return RevocationBlocklist, may be null
     */
    public RevocationBlocklist blocklist() {
        return this.blocklist;
    }

    /**
     * Generate a short-lived access token.
     */
    private String generateAccess(final AuthUser user) {
        final int ttl = this.settingsDao != null
            ? this.settingsDao.getInt("access_token_ttl_seconds", 3600)
            : this.defaultAccessTtl;
        return JWT.create()
            .withSubject(user.name())
            .withClaim(AuthTokenRest.CONTEXT, user.authContext())
            .withClaim(AuthTokenRest.TYPE, TokenType.ACCESS.value())
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(ttl))
            .sign(this.algorithm);
    }

    /**
     * Generate a refresh token and persist its JTI when a DAO is present.
     */
    private String generateRefresh(final AuthUser user) {
        final int ttl = this.settingsDao != null
            ? this.settingsDao.getInt("refresh_token_ttl_seconds", 604800)
            : this.defaultRefreshTtl;
        final UUID jti = UUID.randomUUID();
        final Instant expiresAt = Instant.now().plusSeconds(ttl);
        final String token = JWT.create()
            .withSubject(user.name())
            .withClaim(AuthTokenRest.CONTEXT, user.authContext())
            .withClaim(AuthTokenRest.TYPE, TokenType.REFRESH.value())
            .withJWTId(jti.toString())
            .withIssuedAt(Instant.now())
            .withExpiresAt(expiresAt)
            .sign(this.algorithm);
        if (this.tokenDao != null) {
            this.tokenDao.store(jti, user.name(), "Refresh Token", token, expiresAt, "refresh");
        }
        return token;
    }
}
