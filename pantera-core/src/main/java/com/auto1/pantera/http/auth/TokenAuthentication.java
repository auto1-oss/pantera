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
package com.auto1.pantera.http.auth;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Mechanism to authenticate user by token.
 *
 * @since 0.17
 */
public interface TokenAuthentication {

    /**
     * Authenticate user by token.
     *
     * @param token Token.
     * @return User if authenticated.
     */
    CompletionStage<Optional<AuthUser>> user(String token);

    /**
     * Check a token and say why it is refused, when the check knows a
     * reason beyond "not a valid token": its owner is locked. The default
     * knows none.
     *
     * @param token Token
     * @return The user, or the refusal the check noted
     */
    default CompletionStage<PasswordCheck.Outcome> check(final String token) {
        return this.user(token).thenApply(
            user -> new PasswordCheck.Outcome(user, Optional.empty())
        );
    }
}
