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
package com.auto1.pantera.conda.http;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

/**
 * Short-lived, single-use, path-bound upload tickets for the anaconda-client
 * upload flow.
 *
 * <p>anaconda-client uploads in three steps: an authenticated {@code stage}
 * call returns a {@code post_url}, the package is POSTed to that URL as an
 * S3-style form <em>without</em> any credentials, then an authenticated
 * {@code commit} finishes. The form POST cannot carry the user's token, so
 * the stage step (which is authenticated) embeds a ticket in the
 * {@code post_url}. A ticket names the user, the repository and the exact
 * package key it may write, expires after {@link #TTL}, is signed with the
 * cluster-wide RS256 key pair (so any node can verify it) and is accepted
 * once: its nonce is consumed through a redemption ledger, shared by all
 * nodes when Valkey is configured and process-local otherwise.</p>
 *
 * @since 2.2.9
 */
public final class UploadTickets {

    /**
     * Ticket lifetime.
     */
    public static final Duration TTL = Duration.ofMinutes(10);

    /**
     * Signature algorithm.
     */
    private static final String ALG = "SHA256withRSA";

    /**
     * Base64url encoder without padding (path-safe).
     */
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();

    /**
     * Base64url decoder.
     */
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    /**
     * Signing key.
     */
    private final PrivateKey signing;

    /**
     * Verification key.
     */
    private final PublicKey verifying;

    /**
     * Clock.
     */
    private final Clock clock;

    /**
     * Redemption ledger: marks a ticket nonce as used and answers
     * {@code true} exactly once per nonce.
     */
    private final Function<String, CompletionStage<Boolean>> ledger;

    /**
     * Random source for nonces.
     */
    private final SecureRandom random;

    /**
     * Tickets signed with an ephemeral key pair: valid on this instance only.
     * Production wires the cluster key pair instead.
     */
    public UploadTickets() {
        this(UploadTickets.ephemeral(), Clock.systemUTC());
    }

    /**
     * Tickets signed with the given key pair, redeemed through the given
     * ledger. With several nodes the ledger must be shared (e.g. Valkey
     * {@code SET NX}), otherwise a ticket is single-use per node only.
     * @param signing Private key
     * @param verifying Public key
     * @param ledger Marks a nonce as used; {@code true} on first use only.
     *  It must remember a nonce for at least {@link #TTL}.
     */
    public UploadTickets(final PrivateKey signing, final PublicKey verifying,
        final Function<String, CompletionStage<Boolean>> ledger) {
        this(new KeyPair(verifying, signing), Clock.systemUTC(), ledger);
    }

    /**
     * Tickets signed with the given key pair.
     * @param signing Private key
     * @param verifying Public key
     */
    public UploadTickets(final PrivateKey signing, final PublicKey verifying) {
        this(new KeyPair(verifying, signing), Clock.systemUTC());
    }

    /**
     * Primary ctor.
     * @param keys Key pair
     * @param clock Clock
     */
    UploadTickets(final KeyPair keys, final Clock clock) {
        this(keys, clock, new LocalLedger(clock));
    }

    /**
     * Primary ctor.
     * @param keys Key pair
     * @param clock Clock
     * @param ledger Redemption ledger
     */
    private UploadTickets(final KeyPair keys, final Clock clock,
        final Function<String, CompletionStage<Boolean>> ledger) {
        this.signing = keys.getPrivate();
        this.verifying = keys.getPublic();
        this.clock = clock;
        this.ledger = ledger;
        this.random = new SecureRandom();
    }

    /**
     * Issue a ticket.
     * @param user User the upload is performed for
     * @param repo Repository name
     * @param key Package key ({@code <subdir>/<file>})
     * @return Ticket (base64url, path-safe)
     */
    public String issue(final String user, final String repo, final String key) {
        final byte[] nonce = new byte[16];
        this.random.nextBytes(nonce);
        final byte[] payload = Json.createObjectBuilder()
            .add("u", user)
            .add("r", repo)
            .add("k", key)
            .add("e", this.clock.millis() + UploadTickets.TTL.toMillis())
            .add("n", UploadTickets.ENC.encodeToString(nonce))
            .build().toString().getBytes(StandardCharsets.UTF_8);
        return String.join(
            ".", UploadTickets.ENC.encodeToString(payload),
            UploadTickets.ENC.encodeToString(this.sign(payload))
        );
    }

    /**
     * Redeem a ticket for an upload of the given key.
     * @param ticket Ticket
     * @param repo Repository name the upload targets
     * @param key Package key the upload targets
     * @return User name when the ticket is genuine, unexpired, unused and
     *  issued for exactly this repository and key. A ledger failure rejects
     *  the ticket.
     */
    public CompletionStage<Optional<String>> redeem(final String ticket, final String repo,
        final String key) {
        final Optional<JsonObject> claims = this.verified(ticket)
            .filter(json -> repo.equals(json.getString("r", null)))
            .filter(json -> key.equals(json.getString("k", null)))
            .filter(json -> json.getJsonNumber("e").longValue() >= this.clock.millis());
        final CompletionStage<Optional<String>> res;
        if (claims.isPresent()) {
            final JsonObject json = claims.get();
            res = this.ledger.apply(json.getString("n"))
                .thenApply(
                    first -> Optional.of(json.getString("u"))
                        .filter(user -> Boolean.TRUE.equals(first))
                )
                .exceptionally(err -> Optional.empty());
        } else {
            res = CompletableFuture.completedFuture(Optional.empty());
        }
        return res;
    }

    /**
     * Parse and verify the ticket signature.
     * @param ticket Ticket
     * @return Claims when the signature is valid
     */
    private Optional<JsonObject> verified(final String ticket) {
        final int dot = ticket.indexOf('.');
        Optional<JsonObject> res = Optional.empty();
        if (dot > 0 && dot < ticket.length() - 1) {
            try {
                final byte[] payload = UploadTickets.DEC.decode(ticket.substring(0, dot));
                final byte[] sig = UploadTickets.DEC.decode(ticket.substring(dot + 1));
                final Signature verifier = Signature.getInstance(UploadTickets.ALG);
                verifier.initVerify(this.verifying);
                verifier.update(payload);
                if (verifier.verify(sig)) {
                    try (JsonReader reader = Json.createReader(
                        new StringReader(new String(payload, StandardCharsets.UTF_8))
                    )) {
                        res = Optional.of(reader.readObject());
                    }
                }
            } catch (final IllegalArgumentException | GeneralSecurityException
                | javax.json.JsonException | ClassCastException ex) {
                res = Optional.empty();
            }
        }
        return res;
    }

    /**
     * Sign payload.
     * @param payload Payload
     * @return Signature bytes
     */
    private byte[] sign(final byte[] payload) {
        try {
            final Signature signer = Signature.getInstance(UploadTickets.ALG);
            signer.initSign(this.signing);
            signer.update(payload);
            return signer.sign();
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign conda upload ticket", ex);
        }
    }

    /**
     * Generate an ephemeral RSA key pair.
     * @return Key pair
     */
    private static KeyPair ephemeral() {
        try {
            final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("RSA is not available", ex);
        }
    }

    /**
     * Process-local ledger: single use on this node only.
     * @since 2.2.9
     */
    private static final class LocalLedger implements Function<String, CompletionStage<Boolean>> {

        /**
         * Used nonces with the time they may be forgotten (epoch millis).
         */
        private final Map<String, Long> used;

        /**
         * Clock.
         */
        private final Clock clock;

        /**
         * Ctor.
         * @param clock Clock
         */
        LocalLedger(final Clock clock) {
            this.used = new ConcurrentHashMap<>();
            this.clock = clock;
        }

        @Override
        public CompletionStage<Boolean> apply(final String nonce) {
            final long now = this.clock.millis();
            this.used.values().removeIf(expiry -> expiry < now);
            return CompletableFuture.completedFuture(
                this.used.putIfAbsent(nonce, now + UploadTickets.TTL.toMillis()) == null
            );
        }
    }
}
