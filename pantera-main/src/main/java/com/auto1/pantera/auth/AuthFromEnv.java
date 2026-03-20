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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Authentication based on environment variables.
 * @since 0.3
 */
public final class AuthFromEnv implements Authentication {

    /**
     * Environment name for user.
     */
    public static final String ENV_NAME = "PANTERA_USER_NAME";

    /**
     * Environment name for password.
     */
    private static final String ENV_PASS = "PANTERA_USER_PASS";

    /**
     * Environment variables.
     */
    private final Map<String, String> env;

    /**
     * Default ctor with system environment.
     */
    public AuthFromEnv() {
        this(System.getenv());
    }

    /**
     * Primary ctor.
     * @param env Environment
     */
    public AuthFromEnv(final Map<String, String> env) {
        this.env = env;
    }

    @Override
    @SuppressWarnings("PMD.OnlyOneReturn")
    public Optional<AuthUser> user(final String username, final String password) {
        final Optional<AuthUser> result;
        if (Objects.equals(Objects.requireNonNull(username), this.env.get(AuthFromEnv.ENV_NAME))
            && Objects.equals(Objects.requireNonNull(password), this.env.get(AuthFromEnv.ENV_PASS))) {
            result = Optional.of(new AuthUser(username, "env"));
        } else {
            result = Optional.empty();
        }
        return result;
    }

    @Override
    public String toString() {
        return String.format("%s()", this.getClass().getSimpleName());
    }
}
