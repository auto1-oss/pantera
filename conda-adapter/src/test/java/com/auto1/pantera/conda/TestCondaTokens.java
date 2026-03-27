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
package com.auto1.pantera.conda;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Test tokens.
 */
public class TestCondaTokens implements Tokens {

    private final String token;

    public TestCondaTokens(String token) {
        this.token = token;
    }

    public TestCondaTokens() {
        this("abc123");
    }

    @Override
    public TokenAuthentication auth() {
        return token -> CompletableFuture
            .completedFuture(Optional.of(AuthUser.ANONYMOUS));
    }

    @Override
    public String generate(AuthUser user) {
        return this.token;
    }
}
