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
import io.vertx.core.Vertx;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link JwtTokens}.
 * @since 0.29
 */
class JwtTokensTest {

    /**
     * Test JWT provider.
     */
    private JWTAuth provider;

    @BeforeEach
    void init() {
        this.provider = JWTAuth.create(
            Vertx.vertx(),
            new JWTAuthOptions().addPubSecKey(
                new PubSecKeyOptions().setAlgorithm("HS256").setBuffer("some secret")
            )
        );
    }

    @Test
    void returnsAuth() {
        MatcherAssert.assertThat(
            new JwtTokens(this.provider).auth(),
            new IsInstanceOf(JwtTokenAuth.class)
        );
    }

    @Test
    void generatesToken() {
        MatcherAssert.assertThat(
            new JwtTokens(this.provider).generate(new AuthUser("Oleg", "test")),
            new IsNot<>(Matchers.emptyString())
        );
    }

}
