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
}
