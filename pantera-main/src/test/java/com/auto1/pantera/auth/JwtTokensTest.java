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

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link JwtTokens}.
 * @since 2.1.0
 */
class JwtTokensTest {

    /**
     * RSA private key for signing test tokens.
     */
    private RSAPrivateKey privateKey;

    /**
     * RSA public key for verification.
     */
    private RSAPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        this.privateKey = (RSAPrivateKey) kp.getPrivate();
        this.publicKey = (RSAPublicKey) kp.getPublic();
    }

    @Test
    void returnsAuth() {
        MatcherAssert.assertThat(
            new JwtTokens(this.privateKey, this.publicKey, null, null, null).auth(),
            new IsInstanceOf(UnifiedJwtAuthHandler.class)
        );
    }

    @Test
    void generatesToken() {
        MatcherAssert.assertThat(
            new JwtTokens(this.privateKey, this.publicKey, null, null, null)
                .generate(new AuthUser("Oleg", "test")),
            new IsNot<>(Matchers.emptyString())
        );
    }

}
