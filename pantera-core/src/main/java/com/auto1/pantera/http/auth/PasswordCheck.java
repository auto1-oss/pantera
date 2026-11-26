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
import java.util.concurrent.CompletableFuture;

/**
 * One check of a user name and password against a sign-in chain, with the
 * {@link AuthRefusal} the chain noted when it refused the login.
 *
 * <p>Every entry point that checks a password goes through this class, so a
 * refusal is read on the thread the chain ran on, belongs to this login
 * alone, and never stays on a pooled thread for the next request.</p>
 *
 * @since 2.2.15
 */
public final class PasswordCheck {

    /**
     * Sign-in chain.
     */
    private final Authentication auth;

    /**
     * Ctor.
     *
     * @param auth Sign-in chain
     */
    public PasswordCheck(final Authentication auth) {
        this.auth = auth;
    }

    /**
     * Check on the shared sign-in pool: the chain may ask a database or an
     * identity provider and must not block the calling thread.
     *
     * @param username User name
     * @param password Password
     * @return Outcome
     */
    public CompletableFuture<Outcome> run(final String username, final String password) {
        return CompletableFuture.supplyAsync(
            () -> this.now(username, password), AuthWorkerPool.AUTH_EXECUTOR
        );
    }

    /**
     * Check on the calling thread.
     *
     * @param username User name
     * @param password Password
     * @return Outcome
     */
    public Outcome now(final String username, final String password) {
        // Whatever earlier work left on this pooled thread is not about
        // this login.
        AuthRefusal.take();
        final Optional<AuthUser> user;
        try {
            user = this.auth.user(username, password);
        } catch (final RuntimeException | Error err) {
            AuthRefusal.take();
            throw err;
        }
        final Optional<AuthRefusal> refusal = AuthRefusal.take();
        final Outcome outcome;
        if (user.isPresent()) {
            // A provider that refused does not speak for a chain in which
            // another provider accepted.
            outcome = new Outcome(user, Optional.empty());
        } else {
            outcome = new Outcome(user, refusal);
        }
        return outcome;
    }

    /**
     * Outcome of a check.
     *
     * @param user The user, when the login was accepted
     * @param refusal Why it was refused, when the chain said so
     * @since 2.2.15
     */
    public record Outcome(Optional<AuthUser> user, Optional<AuthRefusal> refusal) {
    }
}
