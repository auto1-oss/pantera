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
package com.auto1.pantera.api.v1.download;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Specification of the hardened direct-download token
 * ({@link DownloadTokens}): every property the 2.2.9 fix must hold, each of
 * which the pre-2.2.9 handler violated — signature checked in constant time,
 * timestamp bounded on BOTH sides, single use via a consumed nonce, bound to
 * the issuing repository and user.
 *
 * @since 2.2.9
 */
final class DownloadTokensTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static final byte[] OTHER = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripCarriesRepoPathAndUser() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final DownloadTokens tokens = tokens(KEY, now);
        final String token = mint(tokens, "repo-a", "docs/x.txt", "alice", "test");
        final DownloadTokens.Verification result = tokens.verify(token, "repo-a")
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat("genuine token verifies", result.status(),
            new IsEqual<>(DownloadTokens.Status.OK));
        MatcherAssert.assertThat("path is carried", result.path(), new IsEqual<>("docs/x.txt"));
        MatcherAssert.assertThat("issuing user is carried", result.user().name(),
            new IsEqual<>("alice"));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final String forged = mint(tokens(OTHER, now), "repo-a", "docs/x.txt", "alice", "test");
        MatcherAssert.assertThat(
            "a token signed under a different key must fail the signature check",
            tokens(KEY, now).verify(forged, "repo-a").toCompletableFuture().get(5, TimeUnit.SECONDS)
                .status(),
            new IsEqual<>(DownloadTokens.Status.INVALID_SIGNATURE)
        );
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final DownloadTokens tokens = tokens(KEY, now);
        final String token = mint(tokens, "repo-a", "docs/x.txt", "alice", "test");
        final int dot = token.indexOf('.');
        final String payload = new String(
            Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8
        ).replace("docs/x.txt", "../../etc/passwd");
        final String tampered = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + token.substring(dot);
        MatcherAssert.assertThat(
            "changing the path under a valid signature must be rejected",
            tokens.verify(tampered, "repo-a").toCompletableFuture().get(5, TimeUnit.SECONDS).status(),
            new IsEqual<>(DownloadTokens.Status.INVALID_SIGNATURE)
        );
    }

    @Test
    void futureDatedTokenIsRejected() throws Exception {
        final AtomicLong issuer = new AtomicLong(1_000_000L + Duration.ofMinutes(10).toMillis());
        final AtomicLong verifier = new AtomicLong(1_000_000L);
        final String token = mint(tokens(KEY, issuer), "repo-a", "docs/x.txt", "alice", "test");
        MatcherAssert.assertThat(
            "a token dated in the future must be rejected (one-sided TTL let it live forever)",
            tokens(KEY, verifier).verify(token, "repo-a").toCompletableFuture()
                .get(5, TimeUnit.SECONDS).status(),
            new IsEqual<>(DownloadTokens.Status.FUTURE_DATED)
        );
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final DownloadTokens tokens = tokens(KEY, now);
        final String token = mint(tokens, "repo-a", "docs/x.txt", "alice", "test");
        now.addAndGet(Duration.ofMinutes(2).toMillis());
        MatcherAssert.assertThat(
            "a token older than the TTL must be rejected",
            tokens.verify(token, "repo-a").toCompletableFuture().get(5, TimeUnit.SECONDS).status(),
            new IsEqual<>(DownloadTokens.Status.EXPIRED)
        );
    }

    @Test
    void replayedTokenIsRejected() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final DownloadTokens tokens = tokens(KEY, now);
        final String token = mint(tokens, "repo-a", "docs/x.txt", "alice", "test");
        MatcherAssert.assertThat("first use is accepted",
            tokens.verify(token, "repo-a").toCompletableFuture().get(5, TimeUnit.SECONDS).status(),
            new IsEqual<>(DownloadTokens.Status.OK));
        MatcherAssert.assertThat(
            "a replayed token must be rejected — single-use means the nonce is consumed",
            tokens.verify(token, "repo-a").toCompletableFuture().get(5, TimeUnit.SECONDS).status(),
            new IsEqual<>(DownloadTokens.Status.REPLAYED)
        );
    }

    @Test
    void tokenForAnotherRepositoryIsRejectedWithoutBurningIt() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final DownloadTokens tokens = tokens(KEY, now);
        final String token = mint(tokens, "repo-a", "docs/x.txt", "alice", "test");
        MatcherAssert.assertThat(
            "presenting a repo-a token at repo-b must be refused",
            tokens.verify(token, "repo-b").toCompletableFuture().get(5, TimeUnit.SECONDS).status(),
            new IsEqual<>(DownloadTokens.Status.REPO_MISMATCH)
        );
        MatcherAssert.assertThat(
            "the mismatch must not consume the nonce — the genuine repo still accepts it once",
            tokens.verify(token, "repo-a").toCompletableFuture().get(5, TimeUnit.SECONDS).status(),
            new IsEqual<>(DownloadTokens.Status.OK)
        );
    }

    @Test
    void malformedTokensAreRejected() throws Exception {
        final DownloadTokens tokens = tokens(KEY, new AtomicLong(1_000_000L));
        MatcherAssert.assertThat("no dot", tokens.verify("nodot", "r").toCompletableFuture()
            .get(5, TimeUnit.SECONDS).status(), new IsEqual<>(DownloadTokens.Status.MALFORMED));
        MatcherAssert.assertThat("bad base64", tokens.verify("%%%.sig", "r").toCompletableFuture()
            .get(5, TimeUnit.SECONDS).status(), new IsEqual<>(DownloadTokens.Status.MALFORMED));
    }

    private static String mint(
        final DownloadTokens tokens, final String repo, final String path,
        final String user, final String context
    ) throws Exception {
        return tokens.issue(repo, path, user, context).toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
    }

    private static DownloadTokens tokens(final byte[] key, final AtomicLong clock) {
        return new DownloadTokens(
            java.util.concurrent.CompletableFuture.completedFuture(key),
            clock::get,
            new InMemoryNonceStore(Duration.ofMinutes(1), clock::get)
        );
    }
}
