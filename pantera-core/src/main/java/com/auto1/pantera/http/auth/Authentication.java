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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Authentication mechanism to verify user.
 */
public interface Authentication {

    /**
     * Find user by credentials.
     * @param username Username
     * @param password Password
     * @return User login if found
     */
    Optional<AuthUser> user(String username, String password);

    /**
     * Check if this authentication provider can handle the given username.
     * Used for domain-based routing to avoid trying providers that don't apply.
     * @param username Username to check
     * @return True if this provider should attempt authentication for this user
     */
    default boolean canHandle(final String username) {
        return true; // Default: handle all users
    }

    /**
     * Get configured user domain patterns for this provider.
     * @return Collection of domain patterns (empty means handle all)
     */
    default Collection<String> userDomains() {
        return Collections.emptyList();
    }

    /**
     * Whether this provider is the authoritative source for the given
     * username. If {@code true} and authentication fails, the chain MUST
     * return empty immediately — it is a security error to fall through
     * to other providers for a username that is known to this provider.
     *
     * <p>Example: {@code AuthFromDb} returns true for any username that
     * exists in the local {@code users} table with
     * {@code auth_provider = 'local'}. Otherwise an attacker could bypass
     * a strong local password by matching a weak SSO password for the
     * same username in a downstream provider.</p>
     *
     * <p>Default: {@code false} — providers make no authority claims.</p>
     *
     * @param username Username being authenticated
     * @return True if this provider owns the user and chain should stop on failure
     */
    default boolean isAuthoritative(final String username) {
        return false;
    }

    /**
     * Abstract decorator for Authentication.
     *
     * @since 0.15
     */
    abstract class Wrap implements Authentication {

        /**
         * Origin authentication.
         */
        private final Authentication auth;

        /**
         * Ctor.
         *
         * @param auth Origin authentication.
         */
        protected Wrap(final Authentication auth) {
            this.auth = auth;
        }

        @Override
        public final Optional<AuthUser> user(final String username, final String password) {
            return this.auth.user(username, password);
        }

        @Override
        public boolean canHandle(final String username) {
            return this.auth.canHandle(username);
        }

        @Override
        public Collection<String> userDomains() {
            return this.auth.userDomains();
        }
    }

    /**
     * Authentication implementation aware of single user with specified password.
     *
     * @since 0.15
     */
    final class Single implements Authentication {

        /**
         * User.
         */
        private final AuthUser user;

        /**
         * Password.
         */
        private final String password;

        /**
         * Ctor.
         *
         * @param user Username.
         * @param password Password.
         */
        public Single(final String user, final String password) {
            this(new AuthUser(user, "single"), password);
        }

        /**
         * Ctor.
         *
         * @param user User
         * @param password Password
         */
        public Single(final AuthUser user, final String password) {
            this.user = user;
            this.password = password;
        }

        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            return Optional.of(name)
                .filter(item -> item.equals(this.user.name()))
                .filter(ignored -> this.password.equals(pass))
                .map(ignored -> this.user);
        }
    }

    /**
     * Joined authentication composes multiple authentication instances into single one.
     * User authenticated if any of authentication instances authenticates the user.
     *
     * @since 0.16
     */
    final class Joined implements Authentication {

        /**
         * Origin authentications.
         */
        private final List<Authentication> origins;

        /**
         * Ctor.
         *
         * @param origins Origin authentications.
         */
        public Joined(final Authentication... origins) {
            this(Arrays.asList(origins));
        }

        /**
         * Ctor.
         *
         * @param origins Origin authentications.
         */
        public Joined(final List<Authentication> origins) {
            this.origins = origins;
        }

        @Override
        public Optional<AuthUser> user(final String user, final String pass) {
            for (final Authentication auth : this.origins) {
                if (!auth.canHandle(user)) {
                    // Provider doesn't handle this username domain - skip
                    continue;
                }
                // Provider can handle this user - try authentication
                final Optional<AuthUser> result = auth.user(user, pass);
                if (result.isPresent()) {
                    // Success — log which provider matched so admins can
                    // diagnose situations like "password changed but old
                    // one still works" (they'll see exactly which provider
                    // accepted the credentials).
                    com.auto1.pantera.http.log.EcsLogger.info(
                        "com.auto1.pantera.http.auth")
                        .message("Authentication succeeded via "
                            + auth.getClass().getSimpleName())
                        .eventCategory("authentication")
                        .eventAction("provider_match")
                        .eventOutcome("success")
                        .field("user.name", user)
                        .field("event.provider",
                            auth.getClass().getSimpleName())
                        .log();
                    return result;
                }
                // SECURITY: authoritative providers stop the chain on failure.
                // If AuthFromDb says "this user is mine" but the password is
                // wrong, we MUST NOT fall through to SSO providers — otherwise
                // an attacker could bypass a strong local password by matching
                // a weak SSO password for the same username.
                if (auth.isAuthoritative(user)) {
                    com.auto1.pantera.http.log.EcsLogger.warn(
                        "com.auto1.pantera.http.auth")
                        .message("Authoritative provider rejected credentials; "
                            + "chain will NOT fall through")
                        .eventCategory("authentication")
                        .eventAction("provider_reject_authoritative")
                        .eventOutcome("failure")
                        .field("user.name", user)
                        .field("event.provider",
                            auth.getClass().getSimpleName())
                        .log();
                    return Optional.empty();
                }
                // Provider matched domain but auth failed
                // If provider has specific domains configured, stop here
                // (don't try other providers for same domain)
                if (!auth.userDomains().isEmpty()) {
                    return Optional.empty();
                }
                // Provider is a catch-all, continue to next
            }
            return Optional.empty();
        }

        @Override
        public String toString() {
            return String.format(
                "%s([%s])",
                this.getClass().getSimpleName(),
                this.origins.stream().map(Object::toString).collect(Collectors.joining(","))
            );
        }
    }
}
