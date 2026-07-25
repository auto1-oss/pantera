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
package com.auto1.pantera.npm.security;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * The registry's own npm package-signing keypair (ECDSA P-256, matching the
 * scheme the public npm registry uses for {@code dist.signatures}).
 *
 * <p>Generated lazily on first use and persisted as a small per-repository
 * storage sidecar ({@code .registry-keys.json}) — the same pattern already
 * used for the dist-tags sidecar ({@code PerVersionLayout}) rather than a
 * DB-backed {@code auth_settings} table, so this stays self-contained inside
 * the npm adapter with no Flyway migration or {@code pantera-main} wiring.
 * A concurrent first publish racing the very first key generation can, in
 * the worst case, generate two keypairs where only the last {@code save()}
 * survives — an accepted, bounded risk during the one-time bootstrap of a
 * brand-new repository (mirrors the existing dist-tags sidecar's
 * last-write-wins semantics under concurrent writes).</p>
 *
 * @since 2.3.0
 */
public final class NpmSigningKeys {

    /**
     * Storage key for the persisted keypair.
     */
    private static final Key KEYS_FILE = new Key.From(".registry-keys.json");

    /**
     * EC curve matching npm's own registry-signing scheme
     * ({@code ecdsa-sha2-nistp256}).
     */
    private static final String CURVE = "secp256r1";

    /**
     * Storage backing this repository.
     */
    private final Storage storage;

    /**
     * Ctor.
     *
     * @param storage Storage backing this repository
     */
    public NpmSigningKeys(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Load the persisted keypair, generating and persisting a new one on
     * first use.
     *
     * @return Completion stage with the signing keypair
     */
    public CompletionStage<SigningKeyPair> keyPair() {
        return this.storage.exists(KEYS_FILE).thenCompose(
            exists -> exists ? this.load() : this.generate()
        );
    }

    /**
     * Fetch just the public half — all {@code GET /-/npm/v1/keys} needs.
     *
     * @return Completion stage with the signing keypair
     */
    public CompletionStage<SigningKeyPair> publicKey() {
        return this.keyPair();
    }

    private CompletionStage<SigningKeyPair> load() {
        return this.storage.value(KEYS_FILE)
            .thenCompose(Content::asBytesFuture)
            .thenApply(bytes -> {
                final JsonObject json = Json.createReader(
                    new StringReader(new String(bytes, StandardCharsets.UTF_8))
                ).readObject();
                return NpmSigningKeys.decode(
                    json.getString("privateKey"), json.getString("publicKey")
                );
            });
    }

    private CompletionStage<SigningKeyPair> generate() {
        final java.security.KeyPair generated;
        try {
            final KeyPairGenerator factory = KeyPairGenerator.getInstance("EC");
            factory.initialize(new ECGenParameterSpec(CURVE));
            generated = factory.generateKeyPair();
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to generate npm registry signing keypair", ex);
        }
        final String privB64 = Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded());
        final String pubB64 = Base64.getEncoder().encodeToString(generated.getPublic().getEncoded());
        final JsonObject json = Json.createObjectBuilder()
            .add("privateKey", privB64)
            .add("publicKey", pubB64)
            .build();
        return this.storage.save(KEYS_FILE, new Content.From(json.toString().getBytes(StandardCharsets.UTF_8)))
            .thenApply(
                ignored -> NpmSigningKeys.build(generated.getPrivate(), generated.getPublic(), pubB64)
            );
    }

    private static SigningKeyPair decode(final String privB64, final String pubB64) {
        try {
            final KeyFactory factory = KeyFactory.getInstance("EC");
            final PrivateKey priv = factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64))
            );
            final PublicKey pub = factory.generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(pubB64))
            );
            return NpmSigningKeys.build(priv, pub, pubB64);
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to decode npm registry signing keypair", ex);
        }
    }

    private static SigningKeyPair build(final PrivateKey priv, final PublicKey pub, final String pubB64) {
        return new SigningKeyPair(priv, pub, NpmSigningKeys.keyId(pubB64), pubB64);
    }

    /**
     * Derive npm's {@code SHA256:<base64>} keyid convention from the
     * DER-encoded SPKI public key bytes.
     *
     * @param pubB64 Base64 DER-encoded public key
     * @return {@code SHA256:}-prefixed keyid
     */
    private static String keyId(final String pubB64) {
        try {
            final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            final byte[] digest = sha256.digest(Base64.getDecoder().decode(pubB64));
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Immutable ECDSA P-256 keypair plus its derived npm-style keyid and
     * the base64 DER-encoded public key (as served at
     * {@code GET /-/npm/v1/keys}).
     *
     * @param privateKey Private signing key
     * @param publicKey Public verification key
     * @param keyId {@code SHA256:<base64>} keyid
     * @param publicKeyBase64 Base64 DER-encoded SPKI public key
     */
    public record SigningKeyPair(
        PrivateKey privateKey, PublicKey publicKey, String keyId, String publicKeyBase64
    ) {
    }
}
