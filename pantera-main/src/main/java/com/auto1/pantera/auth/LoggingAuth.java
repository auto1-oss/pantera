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
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.Optional;

/**
 * Loggin implementation of {@link LoggingAuth}.
 * @since 0.9
 */
public final class LoggingAuth implements Authentication {

    /**
     * Origin authentication.
     */
    private final Authentication origin;

    /**
     * Decorates {@link Authentication} with logger.
     * @param origin Authentication
     */
    public LoggingAuth(final Authentication origin) {
        this.origin = origin;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        final Optional<AuthUser> res = this.origin.user(username, password);
        if (res.isEmpty()) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("Failed to authenticate user via " + this.origin)
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .log();
        } else {
            EcsLogger.info("com.auto1.pantera.auth")
                .message("Successfully authenticated user via " + this.origin)
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("success")
                .field("user.name", username)
                .log();
        }
        return res;
    }
}

