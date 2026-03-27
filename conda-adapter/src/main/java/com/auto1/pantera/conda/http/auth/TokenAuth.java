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
package com.auto1.pantera.conda.http.auth;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Simple in memory implementation of {@link TokenAuthentication}.
 */
public final class TokenAuth implements TokenAuthentication {

    /**
     * Tokens.
     */
    private final TokenAuthentication tokens;

    /**
     * Ctor.
     * @param tokens Tokens and users
     */
    public TokenAuth(final TokenAuthentication tokens) {
        this.tokens = tokens;
    }

    @Override
    public CompletionStage<Optional<AuthUser>> user(final String token) {
        return this.tokens.user(token);
    }
}
