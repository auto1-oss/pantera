/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.artipie.auth;

import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.Authentication;
import com.artipie.http.log.EcsLogger;
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
            EcsLogger.warn("com.artipie.auth")
                .message("Failed to authenticate user")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("event.provider", this.origin.toString())
                .log();
        } else {
            EcsLogger.info("com.artipie.auth")
                .message("Successfully authenticated user")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("success")
                .field("user.name", username)
                .field("event.provider", this.origin.toString())
                .log();
        }
        return res;
    }
}

