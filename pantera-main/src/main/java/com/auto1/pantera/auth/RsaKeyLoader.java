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
import java.io.ByteArrayOutputStream;
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
                + ". Generate with: openssl genpkey -algorithm RSA"
                + " -pkeyopt rsa_keygen_bits:2048 -out private.pem"
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
        } catch (final IllegalStateException ex) { // NOPMD AvoidRethrowingException - rethrow preserves the diagnostic IllegalStateException raised by loadPrivateKey/loadPublicKey so callers can distinguish from the generic Exception wrap below
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
        final byte[] pkcs8;
        if (pem.contains("-----BEGIN PRIVATE KEY-----")) {
            // PKCS#8 — the modern OpenSSL default (openssl genpkey ...)
            final String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            pkcs8 = Base64.getDecoder().decode(base64);
        } else if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            // PKCS#1 — legacy OpenSSL format (openssl genrsa ...). Wrap as PKCS#8.
            final String base64 = pem
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            pkcs8 = wrapPkcs1AsPkcs8(Base64.getDecoder().decode(base64));
        } else {
            throw new IllegalStateException(
                "Unrecognized private key format at " + path
                + ". Expected PEM with '-----BEGIN PRIVATE KEY-----' (PKCS#8)"
                + " or '-----BEGIN RSA PRIVATE KEY-----' (PKCS#1)."
            );
        }
        final KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
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

    /**
     * Wrap a PKCS#1 RSA private key (RSAPrivateKey ASN.1 blob) in a PKCS#8
     * PrivateKeyInfo envelope so Java's {@link PKCS8EncodedKeySpec} can parse it.
     *
     * <p>Structure produced (DER):
     * <pre>
     *   SEQUENCE {
     *     INTEGER 0,                              -- version
     *     SEQUENCE {                              -- AlgorithmIdentifier
     *       OID 1.2.840.113549.1.1.1,             -- rsaEncryption
     *       NULL
     *     },
     *     OCTET STRING &lt;pkcs1 bytes&gt;
     *   }
     * </pre>
     */
    private static byte[] wrapPkcs1AsPkcs8(final byte[] pkcs1) {
        // INTEGER 0 (version)
        final byte[] version = {0x02, 0x01, 0x00};
        // SEQUENCE { OID rsaEncryption, NULL }
        final byte[] algId = {
            0x30, 0x0D,
            0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01,
            0x05, 0x00
        };
        final byte[] octetLen = encodeDerLength(pkcs1.length);
        final int innerLen = version.length + algId.length + 1 + octetLen.length + pkcs1.length;
        final byte[] seqLen = encodeDerLength(innerLen);

        final ByteArrayOutputStream out = new ByteArrayOutputStream(innerLen + seqLen.length + 1);
        out.write(0x30);                    // SEQUENCE
        out.writeBytes(seqLen);
        out.writeBytes(version);
        out.writeBytes(algId);
        out.write(0x04);                    // OCTET STRING
        out.writeBytes(octetLen);
        out.writeBytes(pkcs1);
        return out.toByteArray();
    }

    /** DER length encoding: short form for &lt;128, long form for 128..65535. */
    private static byte[] encodeDerLength(final int len) {
        if (len < 0) {
            throw new IllegalArgumentException("negative length: " + len);
        }
        if (len < 128) {
            return new byte[]{(byte) len};
        }
        if (len < 256) {
            return new byte[]{(byte) 0x81, (byte) len};
        }
        if (len < 65536) {
            return new byte[]{(byte) 0x82, (byte) (len >>> 8), (byte) len};
        }
        throw new IllegalArgumentException("length too large for RSA key: " + len);
    }
}
