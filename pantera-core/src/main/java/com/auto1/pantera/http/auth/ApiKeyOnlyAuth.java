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

/**
 * Authentication that checks a credential with the form of an API key as
 * an API key only.
 *
 * <p>Clients send an API key where a password goes (the Basic password of
 * a repository request, the password field of the sign-in form). A key that
 * is rejected - expired, revoked, issued by another installation - must not
 * be handed on to the password providers: one that asks an external
 * identity provider would present the key there as a wrong password, and a
 * client that repeats the request would add failed logins to the user's
 * directory account.</p>
 *
 * <p>The form is decided by {@link AuthWorkerPool#jwtShaped(String)}: three
 * non-empty parts separated by dots, the first one starting with
 * {@code eyJ}. A credential of that form is answered by the key provider
 * alone, whatever its answer. Every other credential is answered by the
 * password providers alone.</p>
 *
 * @since 2.2.15
 */
public final class ApiKeyOnlyAuth implements Authentication {

    /**
     * Provider that checks API keys.
     */
    private final Authentication keys;

    /**
     * Providers that check passwords.
     */
    private final Authentication passwords;

    /**
     * Ctor for a chain without a key provider: a credential with the form
     * of an API key is rejected.
     *
     * @param passwords Providers that check passwords
     */
    public ApiKeyOnlyAuth(final Authentication passwords) {
        this(new NoKeys(), passwords);
    }

    /**
     * Ctor.
     *
     * @param keys Provider that checks API keys
     * @param passwords Providers that check passwords
     */
    public ApiKeyOnlyAuth(final Authentication keys, final Authentication passwords) {
        this.keys = keys;
        this.passwords = passwords;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        final Optional<AuthUser> res;
        if (AuthWorkerPool.jwtShaped(password)) {
            res = this.keys.user(username, password);
        } else {
            res = this.passwords.user(username, password);
        }
        return res;
    }

    @Override
    public String toString() {
        return String.format(
            "%s(keys=%s, passwords=%s)",
            this.getClass().getSimpleName(), this.keys, this.passwords
        );
    }

    /**
     * Key provider of a chain that has none: rejects every key.
     *
     * @since 2.2.15
     */
    private static final class NoKeys implements Authentication {

        @Override
        public Optional<AuthUser> user(final String username, final String password) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "none";
        }
    }
}
