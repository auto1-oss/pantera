/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.auth;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Utility for matching usernames against domain patterns.
 * Used by authentication providers for domain-based routing.
 * <p>
 * Supported patterns:
 * <ul>
 *   <li>{@code @domain.com} - matches usernames ending with @domain.com</li>
 *   <li>{@code local} - matches usernames without @ (local users)</li>
 *   <li>{@code *} - matches any username (catch-all)</li>
 * </ul>
 * @since 1.20.7
 */
public final class UserDomainMatcher {

    /**
     * Pattern for local users (no domain).
     */
    public static final String LOCAL = "local";

    /**
     * Pattern for catch-all (any user).
     */
    public static final String ANY = "*";

    /**
     * Domain patterns to match against.
     */
    private final List<String> patterns;

    /**
     * Ctor with no patterns (matches all users).
     */
    public UserDomainMatcher() {
        this(Collections.emptyList());
    }

    /**
     * Ctor.
     * @param patterns Domain patterns
     */
    public UserDomainMatcher(final Collection<String> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /**
     * Check if username matches any configured pattern.
     * @param username Username to check
     * @return True if matches (or no patterns configured = matches all)
     */
    public boolean matches(final String username) {
        if (this.patterns.isEmpty()) {
            // No patterns = catch-all
            return true;
        }
        if (username == null || username.isEmpty()) {
            return false;
        }
        for (final String pattern : this.patterns) {
            if (matchesPattern(username, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get configured patterns.
     * @return Unmodifiable collection of patterns
     */
    public Collection<String> patterns() {
        return this.patterns;
    }

    /**
     * Check if username matches a single pattern.
     * @param username Username to check
     * @param pattern Pattern to match against
     * @return True if matches
     */
    private static boolean matchesPattern(final String username, final String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        if (ANY.equals(pattern)) {
            // Catch-all
            return true;
        }
        if (LOCAL.equals(pattern)) {
            // Local user = no @ in username
            return !username.contains("@");
        }
        if (pattern.startsWith("@")) {
            // Domain suffix match
            return username.endsWith(pattern);
        }
        // Exact match
        return username.equals(pattern);
    }

    @Override
    public String toString() {
        return String.format("UserDomainMatcher(%s)", this.patterns);
    }
}
