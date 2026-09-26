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
package com.auto1.pantera.conan;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Item tokens are bound to a repository and expire.
 *
 * @since 2.2.9
 */
final class ItemTokenizerTest {

    /**
     * Vert.x instance.
     */
    private static Vertx vertx;

    @BeforeAll
    static void start() {
        ItemTokenizerTest.vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        ItemTokenizerTest.vertx.close();
    }

    @Test
    void tokenNamesItsRepositoryAndExpires() {
        final JsonObject claims = ItemTokenizerTest.claims(
            ItemTokenizerTest.tokenizer().generateToken("/a/b", "host", "repo-a", "alice")
        );
        MatcherAssert.assertThat(
            "repository claim", claims.getString("repo"), new IsEqual<>("repo-a")
        );
        MatcherAssert.assertThat(
            "user claim", claims.getString("user"), new IsEqual<>("alice")
        );
        MatcherAssert.assertThat(
            "lifetime",
            claims.getLong("exp") - claims.getLong("iat"),
            new IsEqual<>((long) ItemTokenizer.TTL_SECONDS)
        );
    }

    @Test
    void tokenNamesTheUserItWasIssuedTo() {
        final ItemTokenizer tokenizer = ItemTokenizerTest.tokenizer();
        MatcherAssert.assertThat(
            tokenizer.authenticateToken(
                tokenizer.generateToken("/a/b", "host", "repo-a", "alice")
            ).toCompletableFuture().join().orElseThrow().user(),
            new IsEqual<>(java.util.Optional.of("alice"))
        );
    }

    @Test
    void expiredTokenIsRefused() throws Exception {
        final long now = Instant.now().getEpochSecond();
        MatcherAssert.assertThat(
            ItemTokenizerTest.tokenizer().authenticateToken(
                ItemTokenizerTest.signed(
                    new JsonObject().put("path", "/a/b").put("hostname", "host")
                        .put("repo", "repo-a").put("iat", now - 7200).put("exp", now - 3600)
                )
            ).toCompletableFuture().join().isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void tokenWithoutRepositoryIsRefused() {
        MatcherAssert.assertThat(
            ItemTokenizerTest.tokenizer().authenticateToken(
                ItemTokenizerTest.signed(
                    new JsonObject().put("path", "/a/b").put("hostname", "host")
                )
            ).toCompletableFuture().join().isPresent(),
            new IsEqual<>(false)
        );
    }

    private static ItemTokenizer tokenizer() {
        return new ItemTokenizer(
            ItemTokenizerTest.vertx, TestRsaKeys.publicKey(), TestRsaKeys.privateKey()
        );
    }

    private static JsonObject claims(final String token) {
        return new JsonObject(
            new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8)
        );
    }

    /**
     * Sign arbitrary claims with the tokenizer's key pair, the way a token
     * issued before the repository claim (or long ago) looks.
     * @param claims Claims
     * @return RS256 JWT
     */
    private static String signed(final JsonObject claims) {
        try {
            final Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            final String unsigned = String.join(
                ".",
                enc.encodeToString(
                    "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)
                ),
                enc.encodeToString(claims.encode().getBytes(StandardCharsets.UTF_8))
            );
            final Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(TestRsaKeys.privateKey());
            sig.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return String.join(".", unsigned, enc.encodeToString(sig.sign()));
        } catch (final java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
