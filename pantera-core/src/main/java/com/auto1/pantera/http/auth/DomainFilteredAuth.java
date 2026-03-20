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

import java.util.Collection;
import java.util.Optional;

/**
 * Authentication wrapper that filters by username domain.
 * Only attempts authentication if username matches configured domain patterns.
 * @since 1.20.7
 */
public final class DomainFilteredAuth implements Authentication {

    /**
     * Origin authentication.
     */
    private final Authentication origin;

    /**
     * Domain matcher.
     */
    private final UserDomainMatcher matcher;

    /**
     * Provider name for logging.
     */
    private final String name;

    /**
     * Ctor.
     * @param origin Origin authentication
     * @param domains Domain patterns (empty = match all)
     * @param name Provider name for logging
     */
    public DomainFilteredAuth(
        final Authentication origin,
        final Collection<String> domains,
        final String name
    ) {
        this.origin = origin;
        this.matcher = new UserDomainMatcher(domains);
        this.name = name;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        return this.origin.user(username, password);
    }

    @Override
    public boolean canHandle(final String username) {
        return this.matcher.matches(username);
    }

    @Override
    public Collection<String> userDomains() {
        return this.matcher.patterns();
    }

    @Override
    public String toString() {
        return String.format(
            "%s(provider=%s, domains=%s)",
            this.getClass().getSimpleName(),
            this.name,
            this.matcher.patterns()
        );
    }
}
