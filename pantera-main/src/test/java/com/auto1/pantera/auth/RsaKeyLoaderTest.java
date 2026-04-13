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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsaKeyLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsValidKeyPair() throws Exception {
        final KeyPair kp = generateKeyPair();
        final Path privPath = writePem(tempDir, "private.pem", "PRIVATE KEY",
            kp.getPrivate().getEncoded());
        final Path pubPath = writePem(tempDir, "public.pem", "PUBLIC KEY",
            kp.getPublic().getEncoded());
        final RsaKeyLoader loader = new RsaKeyLoader(privPath.toString(), pubPath.toString());
        MatcherAssert.assertThat(loader.privateKey(), new IsNot<>(new IsNull<>()));
        MatcherAssert.assertThat(loader.publicKey(), new IsNot<>(new IsNull<>()));
        MatcherAssert.assertThat(
            loader.publicKey().getModulus(),
            new IsEqual<>(((RSAPublicKey) kp.getPublic()).getModulus())
        );
    }

    @Test
    void throwsOnMissingPrivateKey() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> new RsaKeyLoader("/nonexistent/private.pem", "/nonexistent/public.pem")
        );
    }

    @Test
    void throwsOnInvalidPem() throws Exception {
        final Path bad = tempDir.resolve("bad.pem");
        Files.writeString(bad, "not a pem file");
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> new RsaKeyLoader(bad.toString(), bad.toString())
        );
    }

    private static KeyPair generateKeyPair() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static Path writePem(final Path dir, final String name,
        final String type, final byte[] encoded) throws IOException {
        final Path file = dir.resolve(name);
        final String pem = "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
            + "\n-----END " + type + "-----\n";
        Files.writeString(file, pem);
        return file;
    }
}
