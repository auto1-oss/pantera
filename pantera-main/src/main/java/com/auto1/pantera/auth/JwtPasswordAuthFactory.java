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

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.auth.PanteraAuthFactory;
import com.auto1.pantera.http.auth.AuthFactory;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

/**
 * Factory for JWT-as-password authentication.
 * <p>
 * This factory creates a {@link JwtPasswordAuth} instance that validates
 * JWT tokens used as passwords in Basic Authentication. The JWT secret
 * is read from the pantera.yml configuration.
 * </p>
 * <p>
 * Configuration in pantera.yml:
 * <pre>
 * meta:
 *   jwt:
 *     secret: "${JWT_SECRET}"
 *   credentials:
 *     - type: jwt-password  # This enables JWT-as-password auth
 *     - type: file
 *       path: _credentials.yaml
 * </pre>
 * </p>
 *
 * @since 1.20.7
 */
@PanteraAuthFactory("jwt-password")
public final class JwtPasswordAuthFactory implements AuthFactory {

    /**
     * Shared Vertx instance for JWT validation.
     * Lazily initialized on first use.
     */
    private static volatile Vertx sharedVertx;

    /**
     * Lock for Vertx initialization.
     */
    private static final Object VERTX_LOCK = new Object();

    @Override
    public Authentication getAuthentication(final YamlMapping cfg) {
        final YamlMapping meta = cfg.yamlMapping("meta");
        final YamlMapping jwtYaml = meta != null ? meta.yamlMapping("jwt") : null;
        final String secret = jwtYaml != null ? jwtYaml.string("secret") : null;
        if (secret == null || secret.isEmpty() || "some secret".equals(secret)) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("JWT-as-password auth enabled but using default secret - "
                    + "please configure meta.jwt.secret for production")
                .eventCategory("authentication")
                .eventAction("jwt_password_init")
                .eventOutcome("success")
                .log();
        }
        // Get or create Vertx instance for JWT validation
        final Vertx vertx = getOrCreateVertx();
        final JWTAuth jwtAuth = JWTAuth.create(
            vertx,
            new JWTAuthOptions().addPubSecKey(
                new PubSecKeyOptions().setAlgorithm("HS256").setBuffer(secret)
            )
        );
        // Check if username matching is disabled in config
        boolean requireUsernameMatch = true;
        if (meta != null) {
            final YamlMapping jwtPasswordCfg = meta.yamlMapping("jwt-password");
            if (jwtPasswordCfg != null) {
                final String matchStr = jwtPasswordCfg.string("require-username-match");
                if (matchStr != null) {
                    requireUsernameMatch = Boolean.parseBoolean(matchStr);
                }
            }
        }
        EcsLogger.info("com.auto1.pantera.auth")
            .message(String.format("JWT-as-password authentication initialized: requireUsernameMatch=%s", requireUsernameMatch))
            .eventCategory("authentication")
            .eventAction("jwt_password_init")
            .eventOutcome("success")
            .log();
        return new JwtPasswordAuth(jwtAuth, requireUsernameMatch);
    }

    /**
     * Get or create shared Vertx instance.
     * We need a Vertx instance to create JWTAuth, but we don't want to
     * create a new one each time as it's heavy. This uses the same pattern
     * as other parts of Pantera that need Vertx for non-web operations.
     *
     * @return Shared Vertx instance
     */
    private static Vertx getOrCreateVertx() {
        if (sharedVertx == null) {
            synchronized (VERTX_LOCK) {
                if (sharedVertx == null) {
                    sharedVertx = Vertx.vertx();
                }
            }
        }
        return sharedVertx;
    }

    /**
     * Set shared Vertx instance (for testing or when Vertx is already available).
     *
     * @param vertx Vertx instance to use
     */
    public static void setSharedVertx(final Vertx vertx) {
        synchronized (VERTX_LOCK) {
            sharedVertx = vertx;
        }
    }
}
