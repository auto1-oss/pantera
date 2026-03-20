/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
                .message("Failed to authenticate user")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .field("event.provider", this.origin.toString())
                .log();
        } else {
            EcsLogger.info("com.auto1.pantera.auth")
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

