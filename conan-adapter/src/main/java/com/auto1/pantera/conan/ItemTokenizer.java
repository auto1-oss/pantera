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
package  com.auto1.pantera.conan;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Tokenize repository items via JWT tokens.
 *
 * <p>Signs and verifies Conan per-item tokens (upload/download URL flows)
 * with the cluster-wide RS256 key pair. Before 2.1.2 this class used a
 * hardcoded HMAC secret — see CHANGELOG for migration details. The RSA
 * key pair is the same one passed to {@code JwtTokens}, so HA nodes that
 * share the pair can verify each other's tokens seamlessly.
 *
 * @since 0.1
 */
public class ItemTokenizer {

    /**
     * Field name for host name property of the repository item.
     */
    private static final String HOSTNAME = "hostname";

    /**
     * Field name for path value property of the repository item.
     */
    private static final String PATH = "path";

    /**
     * Basic interface for creating JWT objects.
     */
    private final JWTAuth provider;

    /**
     * Create a new instance backed by the cluster-wide RS256 key pair.
     *
     * @param vertx Vertx core instance
     * @param publicKey RSA public key for verification
     * @param privateKey RSA private key for signing
     */
    public ItemTokenizer(final Vertx vertx, final RSAPublicKey publicKey,
        final RSAPrivateKey privateKey) {
        this.provider = JWTAuth.create(
            vertx,
            new JWTAuthOptions()
                .addPubSecKey(
                    new PubSecKeyOptions()
                        .setAlgorithm("RS256")
                        .setBuffer(pemEncodePublic(publicKey))
                )
                .addPubSecKey(
                    new PubSecKeyOptions()
                        .setAlgorithm("RS256")
                        .setBuffer(pemEncodePrivate(privateKey))
                )
        );
    }

    private static String pemEncodePublic(final RSAPublicKey key) {
        return wrapPem(
            "PUBLIC KEY",
            Base64.getEncoder().encodeToString(key.getEncoded())
        );
    }

    private static String pemEncodePrivate(final RSAPrivateKey key) {
        return wrapPem(
            "PRIVATE KEY",
            Base64.getEncoder().encodeToString(key.getEncoded())
        );
    }

    private static String wrapPem(final String label, final String base64) {
        final StringBuilder sb = new StringBuilder(base64.length() + 128)
            .append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString();
    }

    /**
     * Generates string token for repository item info provided.
     * @param path Path value property of the repository item.
     * @param hostname Host name property of the repository item.
     * @return Java String token in JWT format.
     */
    public String generateToken(final String path, final String hostname) {
        return this.provider.generateToken(
            new JsonObject()
                .put(ItemTokenizer.PATH, path)
                .put(ItemTokenizer.HOSTNAME, hostname),
            // Explicit RS256 — Vert.x's generateToken defaults to HS256
            // which is no keys we configured on the provider.
            new JWTOptions().setAlgorithm("RS256")
        );
    }

    /**
     * Authenticate by token and decode item data.
     * @param token Item token string.
     * @return Decoded item data.
     */
    public CompletionStage<Optional<ItemInfo>> authenticateToken(final String token) {
        return this.provider.authenticate(
            new TokenCredentials(token)
        ).map(
            user -> {
                final JsonObject principal = user.principal();
                Optional<ItemInfo> res = Optional.empty();
                if (principal.containsKey(ItemTokenizer.PATH)
                    && user.containsKey(ItemTokenizer.HOSTNAME)) {
                    res = Optional.of(
                        new ItemInfo(
                            principal.getString(ItemTokenizer.PATH),
                            principal.getString(ItemTokenizer.HOSTNAME)
                        )
                    );
                }
                return res;
            }
        ).toCompletionStage();
    }

    /**
     * Repository item info.
     * @since 0.1
     */
    public static final class ItemInfo {

        /**
         * Path to the item.
         */
        private final String path;

        /**
         * Host name of the client.
         */
        private final String hostname;

        /**
         * Ctor.
         * @param path Path to the item.
         * @param hostname Host name of the client.
         */
        public ItemInfo(final String path, final String hostname) {
            this.path = path;
            this.hostname = hostname;
        }

        /**
         * Path to the item.
         * @return Path to the item.
         */
        public String getPath() {
            return this.path;
        }

        /**
         * Host name of the client.
         * @return Host name of the client.
         */
        public String getHostname() {
            return this.hostname;
        }
    }
}
