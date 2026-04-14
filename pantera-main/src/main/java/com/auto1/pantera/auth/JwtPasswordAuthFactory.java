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
import com.auto1.pantera.settings.JwtSettings;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Optional;

/**
 * Factory for JWT-as-password authentication.
 *
 * <p>Creates a {@link JwtPasswordAuth} backed by a Vert.x {@link JWTAuth}
 * configured for RS256 verification against the cluster's public key — the
 * same key pair {@code JwtTokens} uses to sign API tokens. Reads
 * {@code meta.jwt.public-key-path} from {@code pantera.yml} via
 * {@link com.auto1.pantera.settings.JwtSettings}.
 *
 * <p>Configuration in {@code pantera.yml}:
 * <pre>
 * meta:
 *   jwt:
 *     private-key-path: "${JWT_PRIVATE_KEY_PATH}"
 *     public-key-path:  "${JWT_PUBLIC_KEY_PATH}"
 *   credentials:
 *     - type: jwt-password  # This enables JWT-as-password auth
 *     - type: local
 * </pre>
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
        // cfg is the meta: mapping itself (passed by YamlSettings.initAuth as
        // this.meta()). Do NOT nest with cfg.yamlMapping("meta") — that would
        // look for meta.meta.jwt which doesn't exist and causes the factory to
        // throw "public-key-path is not configured" even though the keys ARE set.
        // JwtSettings.fromYaml expects the meta mapping directly.
        final JwtSettings jwtSettings = JwtSettings.fromYaml(cfg);
        final Optional<String> publicKeyPath = jwtSettings.publicKeyPath();
        if (publicKeyPath.isEmpty()) {
            throw new IllegalStateException(
                "jwt-password auth provider is enabled but meta.jwt.public-key-path is"
                + " not configured. Set meta.jwt.private-key-path and"
                + " meta.jwt.public-key-path (see Admin Guide → Authentication)."
            );
        }
        final RSAPublicKey publicKey = loadRsaPublicKey(Path.of(publicKeyPath.get()));
        final Vertx vertx = getOrCreateVertx();
        final JWTAuth jwtAuth = JWTAuth.create(
            vertx,
            new JWTAuthOptions().addPubSecKey(
                new PubSecKeyOptions()
                    .setAlgorithm("RS256")
                    .setBuffer(pemEncodePublicKey(publicKey))
            )
        );
        boolean requireUsernameMatch = true;
        if (cfg != null) {
            final YamlMapping jwtPasswordCfg = cfg.yamlMapping("jwt-password");
            if (jwtPasswordCfg != null) {
                final String matchStr = jwtPasswordCfg.string("require-username-match");
                if (matchStr != null) {
                    requireUsernameMatch = Boolean.parseBoolean(matchStr);
                }
            }
        }
        EcsLogger.info("com.auto1.pantera.auth")
            .message(String.format(
                "JWT-as-password authentication initialized (RS256): requireUsernameMatch=%s",
                requireUsernameMatch
            ))
            .eventCategory("authentication")
            .eventAction("jwt_password_init")
            .eventOutcome("success")
            .log();
        return new JwtPasswordAuth(jwtAuth, requireUsernameMatch);
    }

    /**
     * Load an RSA public key from a PEM file using {@link RsaKeyLoader}'s
     * decoder. Uses a dummy private-key path so we can reuse the same loader
     * instance — jwt-password only needs the public half for verification,
     * but {@link RsaKeyLoader} validates both. If the configured private key
     * is unreadable we fall back to parsing the public PEM directly.
     */
    private static RSAPublicKey loadRsaPublicKey(final Path publicKeyPath) {
        try {
            final String pem = Files.readString(publicKeyPath);
            final String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
            final byte[] decoded = Base64.getDecoder().decode(base64);
            final java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(
                new java.security.spec.X509EncodedKeySpec(decoded)
            );
        } catch (final Exception ex) {
            throw new IllegalStateException(
                "Failed to load RSA public key for jwt-password auth from "
                + publicKeyPath + ": " + ex.getMessage(), ex
            );
        }
    }

    /**
     * Re-encode an RSA public key as a PEM string — Vert.x's
     * {@code PubSecKeyOptions.setBuffer} expects the PEM form, not raw
     * {@code X509EncodedKeySpec} bytes.
     */
    private static String pemEncodePublicKey(final RSAPublicKey publicKey) {
        final String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        final StringBuilder sb = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        sb.append("-----END PUBLIC KEY-----\n");
        return sb.toString();
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
