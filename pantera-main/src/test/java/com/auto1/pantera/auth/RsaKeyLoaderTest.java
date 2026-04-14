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

import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assertions;

/**
 * Tests for {@link RsaKeyLoader}.
 *
 * <p>Verifies both PEM formats we advertise are accepted:
 * <ul>
 *   <li>PKCS#8 — {@code -----BEGIN PRIVATE KEY-----} (openssl genpkey)</li>
 *   <li>PKCS#1 — {@code -----BEGIN RSA PRIVATE KEY-----} (openssl genrsa / -traditional)</li>
 * </ul>
 *
 * <p>Fixtures in {@code src/test/resources/auth/rsa/} contain the same key
 * material in both formats at both 2048 and 4096 bits, so the two loader
 * paths must produce identical modulus + private exponent.
 */
class RsaKeyLoaderTest {

    private static final String FIXTURES = "auth/rsa/";

    @Test
    void loadsPkcs8Rsa2048() {
        final RsaKeyLoader loader = load("priv-2048-pkcs8.pem", "pub-2048.pem");
        MatcherAssert.assertThat(loader.privateKey().getModulus().bitLength(), Matchers.equalTo(2048));
        MatcherAssert.assertThat(loader.publicKey().getModulus().bitLength(), Matchers.equalTo(2048));
    }

    @Test
    void loadsPkcs1Rsa2048() {
        final RsaKeyLoader loader = load("priv-2048-pkcs1.pem", "pub-2048.pem");
        MatcherAssert.assertThat(loader.privateKey().getModulus().bitLength(), Matchers.equalTo(2048));
    }

    @Test
    void loadsPkcs8Rsa4096() {
        final RsaKeyLoader loader = load("priv-4096-pkcs8.pem", "pub-4096.pem");
        MatcherAssert.assertThat(loader.privateKey().getModulus().bitLength(), Matchers.equalTo(4096));
        MatcherAssert.assertThat(loader.publicKey().getModulus().bitLength(), Matchers.equalTo(4096));
    }

    @Test
    void loadsPkcs1Rsa4096() {
        // Exercises the long-form DER length encoding (0x82 + 2 bytes) in the
        // PKCS#1→PKCS#8 wrapper — the body is ~2349 bytes, far past the
        // 127-byte short-form threshold.
        final RsaKeyLoader loader = load("priv-4096-pkcs1.pem", "pub-4096.pem");
        MatcherAssert.assertThat(loader.privateKey().getModulus().bitLength(), Matchers.equalTo(4096));
    }

    @Test
    void pkcs1AndPkcs8YieldIdenticalKeyMaterial() {
        final RSAPrivateKey fromPkcs1 =
            load("priv-2048-pkcs1.pem", "pub-2048.pem").privateKey();
        final RSAPrivateKey fromPkcs8 =
            load("priv-2048-pkcs8.pem", "pub-2048.pem").privateKey();
        MatcherAssert.assertThat(fromPkcs1.getModulus(), Matchers.equalTo(fromPkcs8.getModulus()));
        MatcherAssert.assertThat(
            fromPkcs1.getPrivateExponent(),
            Matchers.equalTo(fromPkcs8.getPrivateExponent())
        );
    }

    @Test
    void publicKeyMatchesPrivate() {
        final RsaKeyLoader loader = load("priv-2048-pkcs8.pem", "pub-2048.pem");
        final RSAPrivateKey priv = loader.privateKey();
        final RSAPublicKey pub = loader.publicKey();
        MatcherAssert.assertThat(priv.getModulus(), Matchers.equalTo(pub.getModulus()));
        MatcherAssert.assertThat(pub.getPublicExponent(), Matchers.equalTo(BigInteger.valueOf(65_537)));
    }

    @Test
    void rejectsMissingPrivateKey(@TempDir final Path tmp) {
        final IllegalStateException ex = Assertions.assertThrows(
            IllegalStateException.class,
            () -> new RsaKeyLoader(
                tmp.resolve("does-not-exist.pem").toString(),
                resourcePath("pub-2048.pem")
            )
        );
        MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("JWT private key not found"));
    }

    @Test
    void rejectsMissingPublicKey(@TempDir final Path tmp) {
        final IllegalStateException ex = Assertions.assertThrows(
            IllegalStateException.class,
            () -> new RsaKeyLoader(
                resourcePath("priv-2048-pkcs8.pem"),
                tmp.resolve("nope.pem").toString()
            )
        );
        MatcherAssert.assertThat(ex.getMessage(), Matchers.containsString("JWT public key not found"));
    }

    @Test
    void rejectsUnrecognizedPemHeader(@TempDir final Path tmp) throws Exception {
        final Path bogus = tmp.resolve("bogus.pem");
        Files.writeString(bogus, "-----BEGIN DSA PRIVATE KEY-----\nAAAA\n-----END DSA PRIVATE KEY-----\n");
        final IllegalStateException ex = Assertions.assertThrows(
            IllegalStateException.class,
            () -> new RsaKeyLoader(bogus.toString(), resourcePath("pub-2048.pem"))
        );
        MatcherAssert.assertThat(
            ex.getMessage(),
            Matchers.containsString("Unrecognized private key format")
        );
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static RsaKeyLoader load(final String priv, final String pub) {
        final RsaKeyLoader loader = new RsaKeyLoader(resourcePath(priv), resourcePath(pub));
        MatcherAssert.assertThat(loader.privateKey(), Matchers.notNullValue());
        MatcherAssert.assertThat(loader.publicKey(), Matchers.notNullValue());
        return loader;
    }

    private static String resourcePath(final String resource) {
        final URL url = RsaKeyLoaderTest.class.getClassLoader().getResource(FIXTURES + resource);
        if (url == null) {
            throw new IllegalStateException("Missing test fixture: " + FIXTURES + resource);
        }
        return Path.of(URI.create(url.toString())).toString();
    }
}
