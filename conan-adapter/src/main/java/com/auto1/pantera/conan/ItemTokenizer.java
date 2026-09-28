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
     * Field name for the repository the item token was issued for.
     */
    private static final String REPOSITORY = "repo";

    /**
     * Field name for the authenticated user the item token was issued to.
     */
    private static final String USER = "user";

    /**
     * Lifetime of an item token, in seconds. The token is checked when an
     * upload starts, so it only has to outlive the gap between the
     * {@code upload_urls} call and the last file PUT of that upload.
     */
    static final int TTL_SECONDS = 3600;

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
     *
     * <p>The token names the repository it was issued for and expires after
     * {@link #TTL_SECONDS}: every Conan repository shares one key pair (and,
     * on the main port, one host), so without the repository claim a token
     * issued by one repository would be redeemable against another.</p>
     *
     * <p>It also names the authenticated user it was issued to: Conan 1.x
     * sends no credentials to a URL that carries a {@code signature}, so the
     * upload is authorised as that user.</p>
     *
     * @param path Path value property of the repository item.
     * @param hostname Host name property of the repository item.
     * @param repository Name of the repository the item belongs to.
     * @param user Authenticated user the token is issued to.
     * @return Java String token in JWT format.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public String generateToken(final String path, final String hostname,
        final String repository, final String user) {
        return this.provider.generateToken(
            new JsonObject()
                .put(ItemTokenizer.PATH, path)
                .put(ItemTokenizer.HOSTNAME, hostname)
                .put(ItemTokenizer.REPOSITORY, repository)
                .put(ItemTokenizer.USER, user),
            // Explicit RS256 — Vert.x's generateToken defaults to HS256
            // which is no keys we configured on the provider.
            new JWTOptions().setAlgorithm("RS256")
                .setExpiresInSeconds(ItemTokenizer.TTL_SECONDS)
        );
    }

    /**
     * Authenticate by token and decode item data.
     * @param token Item token string.
     * @return Decoded item data; empty for a token that is invalid, expired
     *  or lacks any of the item claims.
     */
    public CompletionStage<Optional<ItemInfo>> authenticateToken(final String token) {
        return this.provider.authenticate(
            new TokenCredentials(token)
        ).map(
            user -> {
                // Vert.x keeps the registered claims (exp, iat) in the user's
                // attributes, not in the principal; it has already refused an
                // expired token, this only refuses a token issued without exp.
                final JsonObject principal = user.principal();
                Optional<ItemInfo> res = Optional.empty();
                if (principal.containsKey(ItemTokenizer.PATH)
                    && principal.containsKey(ItemTokenizer.HOSTNAME)
                    && principal.containsKey(ItemTokenizer.REPOSITORY)
                    && user.containsKey("exp")) {
                    res = Optional.of(
                        new ItemInfo(
                            principal.getString(ItemTokenizer.PATH),
                            principal.getString(ItemTokenizer.HOSTNAME),
                            principal.getString(ItemTokenizer.REPOSITORY),
                            Optional.ofNullable(principal.getString(ItemTokenizer.USER))
                                .filter(usr -> !usr.isBlank())
                        )
                    );
                }
                return res;
            }
        ).otherwise(Optional.empty()).toCompletionStage();
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
         * Repository the token was issued for.
         */
        private final String repository;

        /**
         * User the token was issued to, empty for a token without one.
         */
        private final Optional<String> user;

        /**
         * Ctor.
         * @param path Path to the item.
         * @param hostname Host name of the client.
         * @param repository Repository the token was issued for.
         * @param user User the token was issued to.
         */
        public ItemInfo(final String path, final String hostname, final String repository,
            final Optional<String> user) {
            this.path = path;
            this.hostname = hostname;
            this.repository = repository;
            this.user = user;
        }

        /**
         * User the token was issued to.
         * @return User name, empty for a token issued without one.
         */
        public Optional<String> user() {
            return this.user;
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

        /**
         * Repository the token was issued for.
         * @return Repository name.
         */
        public String getRepository() {
            return this.repository;
        }
    }
}
