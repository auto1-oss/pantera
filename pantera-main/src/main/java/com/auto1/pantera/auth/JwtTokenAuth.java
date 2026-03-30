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
import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;

/**
 * Token authentication with Vert.x {@link io.vertx.ext.auth.jwt.JWTAuth} under the hood.
 * When a {@link UserTokenDao} is provided every token must carry a {@code jti} claim whose
 * UUID is present and non-revoked in the {@code user_tokens} table.  This prevents forged
 * tokens from being accepted even when the signing secret is known.
 */
public final class JwtTokenAuth implements TokenAuthentication {

    /**
     * Jwt auth provider.
     */
    private final JWTAuth provider;

    /**
     * Token DAO for JTI allowlist validation.
     * Null when running without a database (YAML-only mode) — falls back to
     * signature-only validation (legacy behaviour).
     */
    private final UserTokenDao tokenDao;

    /**
     * @param provider Jwt auth provider (signature-only, no JTI enforcement)
     */
    public JwtTokenAuth(final JWTAuth provider) {
        this(provider, null);
    }

    /**
     * @param provider Jwt auth provider
     * @param tokenDao DAO used to validate that the token's jti exists and is not revoked.
     *                 Pass {@code null} to disable JTI enforcement (legacy / no-DB mode).
     */
    public JwtTokenAuth(final JWTAuth provider, final UserTokenDao tokenDao) {
        this.provider = provider;
        this.tokenDao = tokenDao;
    }

    @Override
    public CompletionStage<Optional<AuthUser>> user(final String token) {
        return this.provider
            .authenticate(new TokenCredentials(token))
            .toCompletionStage()
            .thenApplyAsync(
                user -> {
                    if (user == null) {
                        return Optional.<AuthUser>empty();
                    }
                    final var principal = user.principal();
                    if (!principal.containsKey(AuthTokenRest.SUB)
                        || !principal.containsKey(AuthTokenRest.CONTEXT)) {
                        return Optional.<AuthUser>empty();
                    }
                    if (this.tokenDao != null && !this.jtiValid(principal.getString("jti"))) {
                        EcsLogger.warn("com.auto1.pantera.auth")
                            .message("Token rejected: missing or unknown jti")
                            .eventCategory("authentication")
                            .eventAction("token_validate")
                            .eventOutcome("failure")
                            .log();
                        return Optional.<AuthUser>empty();
                    }
                    return Optional.of(
                        new AuthUser(
                            principal.getString(AuthTokenRest.SUB),
                            principal.getString(AuthTokenRest.CONTEXT)
                        )
                    );
                },
                ForkJoinPool.commonPool()
            )
            .exceptionally(err -> Optional.empty());
    }

    /**
     * Validate that the given jti string is a known, non-revoked token UUID.
     */
    private boolean jtiValid(final String jtiStr) {
        if (jtiStr == null || jtiStr.isEmpty()) {
            return false;
        }
        try {
            return this.tokenDao.isValid(UUID.fromString(jtiStr));
        } catch (final IllegalArgumentException ex) {
            return false;
        }
    }
}
