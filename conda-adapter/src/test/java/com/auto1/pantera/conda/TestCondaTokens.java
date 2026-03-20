/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
