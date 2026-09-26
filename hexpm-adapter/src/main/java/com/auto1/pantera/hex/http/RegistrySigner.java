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
package com.auto1.pantera.hex.http;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Signs Hex registry records the way hex_core verifies them: an RSA
 * PKCS#1 v1.5 signature over the protobuf payload with SHA-512, checked
 * against the PEM public key the repository serves at {@code /public_key}.
 *
 * @since 2.2.9
 */
public final class RegistrySigner {

    /**
     * Signature algorithm used by hex_core.
     */
    private static final String ALG = "SHA512withRSA";

    /**
     * Private key.
     */
    private final PrivateKey signing;

    /**
     * Public key.
     */
    private final PublicKey verifying;

    /**
     * Signer with an ephemeral key pair (valid for this instance only).
     * Production wires the cluster key pair instead.
     */
    public RegistrySigner() {
        this(RegistrySigner.ephemeral());
    }

    /**
     * Signer with the given RSA key pair.
     * @param signing Private key
     * @param verifying Public key
     */
    public RegistrySigner(final PrivateKey signing, final PublicKey verifying) {
        this(new KeyPair(verifying, signing));
    }

    /**
     * Primary ctor.
     * @param keys Key pair
     */
    private RegistrySigner(final KeyPair keys) {
        this.signing = keys.getPrivate();
        this.verifying = keys.getPublic();
    }

    /**
     * Sign a registry payload.
     * @param payload Payload bytes
     * @return Signature bytes
     */
    public byte[] sign(final byte[] payload) {
        try {
            final Signature signer = Signature.getInstance(RegistrySigner.ALG);
            signer.initSign(this.signing);
            signer.update(payload);
            return signer.sign();
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign the Hex registry", ex);
        }
    }

    /**
     * Public key in PEM (SubjectPublicKeyInfo) form.
     * @return PEM bytes
     */
    public byte[] publicKeyPem() {
        final String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(this.verifying.getEncoded());
        return String.format("-----BEGIN PUBLIC KEY-----\n%s\n-----END PUBLIC KEY-----\n", body)
            .getBytes(StandardCharsets.US_ASCII);
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
}
