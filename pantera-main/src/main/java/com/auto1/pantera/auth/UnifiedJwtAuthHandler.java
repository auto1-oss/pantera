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
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.log.EcsLogger;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;

/**
 * Unified JWT authentication handler for both HTTP ports.
 * Verifies RS256-signed tokens, routes validation by token type, and
 * enforces revocation via the blocklist (access tokens) or DB JTI check
 * (refresh and API tokens).
 * @since 2.1.0
 */
public final class UnifiedJwtAuthHandler implements TokenAuthentication {

    /**
     * Auth0 JWT verifier configured with the RS256 public key and required claims.
     */
    private final JWTVerifier verifier;

    /**
     * DAO for JTI persistence/lookup. Null disables DB-backed validation.
     */
    private final UserTokenDao tokenDao;

    /**
     * In-memory / Valkey revocation blocklist. Null disables blocklist checks.
     */
    private final RevocationBlocklist blocklist;

    /**
     * Per-request enabled-state gate. Never null — defaults to
     * {@link UserEnabledCheck#ALWAYS_ENABLED} when not supplied.
     */
    private final UserEnabledCheck enabledCheck;

    /**
     * Ctor.
     * @param publicKey RSA public key used for RS256 verification
     * @param tokenDao DAO for JTI validation; {@code null} disables JTI enforcement
     * @param blocklist Revocation blocklist; {@code null} disables revocation checks
     */
    public UnifiedJwtAuthHandler(
        final RSAPublicKey publicKey,
        final UserTokenDao tokenDao,
        final RevocationBlocklist blocklist
    ) {
        this(publicKey, tokenDao, blocklist, UserEnabledCheck.ALWAYS_ENABLED);
    }

    /**
     * Ctor with explicit enabled-state gate.
     * @param publicKey RSA public key used for RS256 verification
     * @param tokenDao DAO for JTI validation; {@code null} disables JTI enforcement
     * @param blocklist Revocation blocklist; {@code null} disables revocation checks
     * @param enabledCheck Per-request check that the subject is still
     *     enabled. Pass {@link UserEnabledCheck#ALWAYS_ENABLED} to skip
     *     the check (not recommended in production).
     */
    public UnifiedJwtAuthHandler(
        final RSAPublicKey publicKey,
        final UserTokenDao tokenDao,
        final RevocationBlocklist blocklist,
        final UserEnabledCheck enabledCheck
    ) {
        this.verifier = JWT.require(Algorithm.RSA256(publicKey))
            .withClaimPresence(AuthTokenRest.JTI)
            .withClaimPresence(AuthTokenRest.TYPE)
            .withClaimPresence(AuthTokenRest.SUB)
            .withClaimPresence(AuthTokenRest.CONTEXT)
            .build();
        this.tokenDao = tokenDao;
        this.blocklist = blocklist;
        this.enabledCheck = enabledCheck != null
            ? enabledCheck : UserEnabledCheck.ALWAYS_ENABLED;
    }

    @Override
    public CompletionStage<Optional<AuthUser>> user(final String token) {
        return CompletableFuture.supplyAsync(
            () -> this.validate(token),
            ForkJoinPool.commonPool()
        );
    }

    /**
     * Decode and return the token type without full validation.
     * Returns {@code null} if the token is invalid or the type claim is unrecognised.
     * @param token JWT string
     * @return TokenType or {@code null}
     */
    public TokenType tokenType(final String token) {
        try {
            final DecodedJWT decoded = this.verifier.verify(token);
            return TokenType.fromClaim(decoded.getClaim(AuthTokenRest.TYPE).asString());
        } catch (final JWTVerificationException ex) {
            return null;
        }
    }

    /**
     * Perform full token validation: signature, expiry, required claims, and
     * type-specific revocation/DB checks.
     * @param token JWT string
     * @return Authenticated user if valid, empty otherwise
     */
    private Optional<AuthUser> validate(final String token) {
        final DecodedJWT decoded;
        try {
            decoded = this.verifier.verify(token);
        } catch (final JWTVerificationException ex) {
            return Optional.empty();
        }
        final String sub = decoded.getSubject();
        final String context = decoded.getClaim(AuthTokenRest.CONTEXT).asString();
        final String jti = decoded.getId();
        final TokenType type = TokenType.fromClaim(
            decoded.getClaim(AuthTokenRest.TYPE).asString()
        );
        if (sub == null || context == null || jti == null || type == null) {
            return Optional.empty();
        }
        switch (type) {
            case ACCESS:
                if (this.blocklist != null
                    && (this.blocklist.isRevokedJti(jti) || this.blocklist.isRevokedUser(sub))) {
                    EcsLogger.info("com.auto1.pantera.auth")
                        .message("Access token rejected: blocklisted")
                        .eventCategory("authentication")
                        .eventAction("token_validate")
                        .eventOutcome("failure")
                        .field("user.name", sub)
                        .log();
                    return Optional.empty();
                }
                break;
            case REFRESH:
            case API:
                if (this.tokenDao != null) {
                    try {
                        if (!this.tokenDao.isValidForUser(UUID.fromString(jti), sub)) {
                            EcsLogger.warn("com.auto1.pantera.auth")
                                .message("Token rejected: JTI not found or wrong user")
                                .eventCategory("authentication")
                                .eventAction("token_validate")
                                .eventOutcome("failure")
                                .field("user.name", sub)
                                .log();
                            return Optional.empty();
                        }
                    } catch (final IllegalArgumentException ex) {
                        return Optional.empty();
                    }
                }
                break;
            default:
                return Optional.empty();
        }
        // Per-request enabled-state gate. If the subject has been
        // disabled in Pantera since the token was issued, reject here.
        // This is the only check that lets an admin revoke live
        // sessions and long-lived API tokens instantly: the token
        // itself is still valid on paper, but the check dismisses it.
        if (!this.enabledCheck.isEnabled(sub)) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("Token rejected: user is disabled")
                .eventCategory("authentication")
                .eventAction("token_validate")
                .eventOutcome("failure")
                .field("user.name", sub)
                .log();
            return Optional.empty();
        }
        return Optional.of(new AuthUser(sub, context));
    }
}
