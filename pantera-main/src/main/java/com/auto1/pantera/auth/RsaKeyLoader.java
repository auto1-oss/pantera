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

import com.auto1.pantera.http.log.EcsLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads RSA key pair from PEM files for RS256 JWT signing.
 * Fails fast with actionable error messages on misconfiguration.
 */
public final class RsaKeyLoader {

    private final RSAPrivateKey privKey;
    private final RSAPublicKey pubKey;

    public RsaKeyLoader(final String privateKeyPath, final String publicKeyPath) {
        final Path privPath = Path.of(privateKeyPath);
        final Path pubPath = Path.of(publicKeyPath);
        if (!Files.isReadable(privPath)) {
            throw new IllegalStateException(
                "JWT private key not found at " + privateKeyPath
                + ". Generate with: openssl genrsa -out private.pem 2048"
            );
        }
        if (!Files.isReadable(pubPath)) {
            throw new IllegalStateException(
                "JWT public key not found at " + publicKeyPath
                + ". Generate with: openssl rsa -in private.pem -pubout -out public.pem"
            );
        }
        try {
            this.privKey = loadPrivateKey(privPath);
            this.pubKey = loadPublicKey(pubPath);
        } catch (final IllegalStateException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to load RSA key pair: " + ex.getMessage(), ex);
        }
        EcsLogger.info("com.auto1.pantera.auth")
            .message("RS256 key pair loaded successfully")
            .eventCategory("configuration")
            .eventAction("key_load")
            .eventOutcome("success")
            .log();
    }

    public RSAPrivateKey privateKey() {
        return this.privKey;
    }

    public RSAPublicKey publicKey() {
        return this.pubKey;
    }

    private static RSAPrivateKey loadPrivateKey(final Path path) throws Exception {
        final String pem = Files.readString(path);
        final String base64 = pem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        final byte[] decoded = Base64.getDecoder().decode(base64);
        final KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private static RSAPublicKey loadPublicKey(final Path path) throws Exception {
        final String pem = Files.readString(path);
        final String base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        final byte[] decoded = Base64.getDecoder().decode(base64);
        final KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(decoded));
    }
}
