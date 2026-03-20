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
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link JwtTokenAuth}.
 * @since 0.29
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class JwtTokenAuthTest {

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
    void returnsUser() {
        final String name = "Alice";
        final String cntx = "local";
        MatcherAssert.assertThat(
            new JwtTokenAuth(this.provider).user(
                this.provider.generateToken(new JsonObject().put("sub", name).put("context", cntx))
            ).toCompletableFuture().join().get(),
            new IsEqual<>(new AuthUser(name, cntx))
        );
    }

    @Test
    void returnsEmptyWhenSubIsNotPresent() {
        MatcherAssert.assertThat(
            new JwtTokenAuth(this.provider).user(
                this.provider.generateToken(new JsonObject().put("context", "any"))
            ).toCompletableFuture().join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void returnsEmptyWhenContextIsNotPresent() {
        MatcherAssert.assertThat(
            new JwtTokenAuth(this.provider).user(
                this.provider.generateToken(new JsonObject().put("sub", "Alex"))
            ).toCompletableFuture().join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void returnsEmptyWhenTokenIsNotValid() {
        MatcherAssert.assertThat(
            new JwtTokenAuth(this.provider).user("not valid token")
                .toCompletableFuture().join().isEmpty(),
            new IsEqual<>(true)
        );
    }

}
