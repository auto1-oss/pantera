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

import com.auto1.pantera.http.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Short-lived, single-use, HMAC-signed capability for the browser-native
 * direct download ({@code GET .../artifact/download-direct?token=}).
 *
 * <p>Token = {@code base64url(payload) "." base64url(HMAC-SHA256(payload))}
 * with payload {@code repo \n path \n issuedAtMillis \n nonce \n user \n
 * context}. Verification, in order: shape, signature (constant time via
 * {@link MessageDigest#isEqual}), timestamp bounded on BOTH sides (not
 * expired, not future-dated beyond clock skew), repository match, and
 * finally atomic nonce consumption — so a mismatch never burns a genuine
 * token, and a verified token is spent before any byte is streamed.</p>
 *
 * <p>The signing key is supplied as a future so production can resolve it
 * off the event loop (it may read the shared settings table); the first
 * issue/verify waits on it.</p>
 *
 * @since 2.2.9
 */
public final class DownloadTokens {

    /**
     * Token lifetime.
     */
    public static final Duration TTL = Duration.ofSeconds(60);

    /**
     * Tolerated clock skew for the not-before side.
     */
    static final Duration SKEW = Duration.ofSeconds(30);

    private static final String HMAC = "HmacSHA256";

    private static final int NONCE_BYTES = 16;

    private static final int FIELDS = 6;

    private final CompletionStage<byte[]> key;

    private final LongSupplier clock;

    private final NonceStore nonces;

    private final SecureRandom random;

    /**
     * Ctor.
     *
     * @param key Signing key (may still be resolving)
     * @param clock Millisecond clock
     * @param nonces Single-use ledger
     */
    public DownloadTokens(
        final CompletionStage<byte[]> key, final LongSupplier clock, final NonceStore nonces
    ) {
        this.key = key;
        this.clock = clock;
        this.nonces = nonces;
        this.random = new SecureRandom();
    }

    /**
     * Mint a token for the authenticated issuer.
     *
     * @param repo Repository name
     * @param path Artifact path within the repository
     * @param user Issuing user name
     * @param context Issuing user's auth context
     * @return Token string
     */
    public CompletionStage<String> issue(
        final String repo, final String path, final String user, final String context
    ) {
        final byte[] nonce = new byte[NONCE_BYTES];
        this.random.nextBytes(nonce);
        final String payload = String.join(
            "\n", repo, path, Long.toString(this.clock.getAsLong()),
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
            user, context
        );
        return this.key.thenApply(secret ->
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac(secret, payload))
        );
    }

    /**
     * Verify a presented token against the repository named in the URL and
     * spend its nonce on success.
     *
     * @param token Presented token
     * @param repo Repository from the request URL
     * @return Verification outcome (never a failed stage)
     */
    public CompletionStage<Verification> verify(final String token, final String repo) {
        return this.key.thenCompose(secret -> this.verifyWith(secret, token, repo));
    }

    private CompletionStage<Verification> verifyWith(
        final byte[] secret, final String token, final String repo
    ) {
        final String[] parts;
        try {
            parts = decode(secret, token);
        } catch (final IllegalArgumentException malformed) {
            return CompletableFuture.completedFuture(Verification.failed(Status.MALFORMED));
        }
        if (parts.length == 0) {
            return CompletableFuture.completedFuture(Verification.failed(Status.INVALID_SIGNATURE));
        }
        final Status timing = this.checkTime(Long.parseLong(parts[2]));
        if (timing != Status.OK) {
            return CompletableFuture.completedFuture(Verification.failed(timing));
        }
        if (!repo.equals(parts[0])) {
            return CompletableFuture.completedFuture(Verification.failed(Status.REPO_MISMATCH));
        }
        return this.nonces.consume(parts[3]).thenApply(first -> first
            ? new Verification(Status.OK, parts[1], new AuthUser(parts[4], parts[5]))
            : Verification.failed(Status.REPLAYED)
        );
    }

    /**
     * Split, decode and authenticate the token.
     *
     * @param secret Signing key
     * @param token Presented token
     * @return Payload fields when the signature verifies, an empty array
     *  when it does not
     * @throws IllegalArgumentException when the token is malformed
     */
    private static String[] decode(final byte[] secret, final String token) {
        final int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new IllegalArgumentException("malformed");
        }
        final byte[] payloadBytes = Base64.getUrlDecoder().decode(token.substring(0, dot));
        final byte[] presented = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        final String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        final byte[] expected = mac(secret, payload);
        if (!MessageDigest.isEqual(expected, presented)) {
            return new String[0];
        }
        final String[] parts = payload.split("\n", -1);
        if (parts.length != FIELDS) {
            throw new IllegalArgumentException("malformed");
        }
        try {
            Long.parseLong(parts[2]);
        } catch (final NumberFormatException bad) {
            throw new IllegalArgumentException("malformed", bad);
        }
        return parts;
    }

    private Status checkTime(final long issued) {
        final long now = this.clock.getAsLong();
        if (issued > now + SKEW.toMillis()) {
            return Status.FUTURE_DATED;
        }
        if (now - issued > TTL.toMillis()) {
            return Status.EXPIRED;
        }
        return Status.OK;
    }

    private static byte[] mac(final byte[] secret, final String payload) {
        try {
            final Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (final java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC signing failed", ex);
        }
    }

    /**
     * Verification outcome.
     */
    public enum Status {
        /** Token accepted and spent. */
        OK,
        /** Not a well-formed token. */
        MALFORMED,
        /** Signature does not match (wrong key or tampered payload). */
        INVALID_SIGNATURE,
        /** Issued too long ago. */
        EXPIRED,
        /** Issued in the future beyond clock skew. */
        FUTURE_DATED,
        /** Presented at a different repository than it was issued for. */
        REPO_MISMATCH,
        /** Nonce already spent. */
        REPLAYED
    }

    /**
     * Verification result: status plus, on success, the path and issuer.
     *
     * @param status Outcome
     * @param path Artifact path (success only)
     * @param user Issuing user (success only)
     */
    public record Verification(Status status, String path, AuthUser user) {

        static Verification failed(final Status status) {
            return new Verification(status, null, null);
        }
    }
}
